package io.oryxos.core.channel;

import io.oryxos.core.agent.AgentExecutionService;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.session.Message;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 入站消息共享编排（017 FR-010 的语义收敛点）：去重 → 路由 → 私聊/群聊分流 → 回复 → 审计。
 *
 * <p>契约行为 B1~B10（specs/017 contracts/inbound-channel-contract.md）全部在此实现，参数化契约测试集 （飞书档 +
 * 测试桩档）对本类钉死；渠道适配器只做协议转换，不得复制这些逻辑。
 *
 * <p>时序（FR-008）：{@link #onMessage} 在平台确认线程内只做去重与任务提交（飞书要求 3 秒内返回）， ReAct 推理与回发在 {@link
 * AgentExecutionService#triggerAsync} 的虚拟线程里跑；「处理中」提示用另一个虚拟线程 + {@link CountDownLatch} 纯同步原语实现（宪法
 * VII：不引入 CompletableFuture 等异步编程模型）。
 */
public class InboundMessageService {

  private static final Logger LOG = LoggerFactory.getLogger(InboundMessageService.class);

  private static final String DEDUP_KEY_SEPARATOR = ":";

  static final String UNSUPPORTED_TYPE_REPLY = "当前仅支持文本提问，请用文字描述你的问题。";
  static final String AGENT_UNAVAILABLE_REPLY = "Agent 暂不可用（未找到绑定的 Agent），请联系管理员。";
  static final String FAILURE_REPLY = "抱歉，这次处理失败了，请稍后重试或联系管理员。";
  static final String PROCESSING_REPLY = "已收到，正在处理中，请稍候…";
  static final String NEW_SESSION_REPLY = "已开启新会话，之前的对话上下文已清空。";
  private static final String NEW_SESSION_COMMAND = "/new";

  private final AgentService agentService;
  private final SessionManager sessionManager;
  private final ProfileRegistry profileRegistry;
  private final AgentExecutionService executionService;
  private final MessageDeduplicator deduplicator;
  private final InboundMediaEnricher mediaEnricher;
  private final Duration processingNoticeDelay;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "全部协作者都是 Runtime 装配的单例，共享引用正是意图")
  public InboundMessageService(
      AgentService agentService,
      SessionManager sessionManager,
      ProfileRegistry profileRegistry,
      AgentExecutionService executionService,
      MessageDeduplicator deduplicator,
      InboundMediaEnricher mediaEnricher,
      Duration processingNoticeDelay) {
    this.agentService = agentService;
    this.sessionManager = sessionManager;
    this.profileRegistry = profileRegistry;
    this.executionService = executionService;
    this.deduplicator = deduplicator;
    this.mediaEnricher = mediaEnricher == null ? new DefaultInboundMediaEnricher() : mediaEnricher;
    this.processingNoticeDelay = processingNoticeDelay;
  }

  /** 在昂贵预处理（飞书入站图下载等）前占用 message_id。返回 false 表示重复事件，调用方应直接丢弃且勿再下载。 */
  public boolean tryClaim(String channelName, String messageId) {
    return deduplicator.markIfFirst(channelName + DEDUP_KEY_SEPARATOR + messageId);
  }

  /**
   * 归一化消息唯一入口：确认线程内快速返回（去重 + 提交），推理与回发在虚拟线程（B5）。
   *
   * @param msg 归一化入站消息（群聊必须已在适配器层过滤为 @ 机器人的消息）
   * @param replyVia 回复通道（即产生本消息的适配器）
   */
  public void onMessage(InboundMessage msg, InboundChannelAdapter replyVia) {
    onMessage(msg, replyVia, true);
  }

  /** 调用方已通过 {@link #tryClaim} 占用 message_id 时使用（避免下载完成后二次去重把合法首条丢掉）。 */
  public void onClaimedMessage(InboundMessage msg, InboundChannelAdapter replyVia) {
    onMessage(msg, replyVia, false);
  }

  private void onMessage(
      InboundMessage msg, InboundChannelAdapter replyVia, boolean claimMessageId) {
    // B1：同一渠道同一 message_id 只处理一次（平台超时重推场景用户只收到一条回答）
    if (claimMessageId
        && !deduplicator.markIfFirst(msg.channelName() + DEDUP_KEY_SEPARATOR + msg.messageId())) {
      LOG.info("渠道 {} 重复事件已忽略: {}", sanitize(msg.channelName()), sanitize(msg.messageId()));
      return;
    }
    // B4：群聊回复引用原消息使问答可对应；私聊直发
    String replyTo = msg.chatKind() == ChatKind.GROUP ? msg.messageId() : null;
    // B7：不可处理的消息（非文本且无附件）回能力说明，不进推理、不落执行记录
    String agentInput = mediaEnricher.toAgentInput(msg);
    if (!msg.processable() || agentInput.isBlank()) {
      safeReply(replyVia, msg.chatId(), UNSUPPORTED_TYPE_REPLY, replyTo);
      return;
    }
    String agent = replyVia.boundAgent();
    // B9：绑定 Agent 不存在（底座无停用态，不存在即不可用）——可读回复 + 失败执行留痕
    if (profileRegistry.get(agent).isEmpty()) {
      safeReply(replyVia, msg.chatId(), AGENT_UNAVAILABLE_REPLY, replyTo);
      executionService.triggerAsync(
          agent,
          msg.channelType(),
          null,
          () -> {
            throw new IllegalStateException(
                "渠道 " + msg.channelName() + " 绑定的 Agent " + agent + " 不存在");
          });
      return;
    }
    // 私聊 /new：清空固定三元组会话历史（飞书等 IM 无独立「新会话」键）
    if (msg.chatKind() == ChatKind.P2P && msg.textual() && isNewSessionCommand(agentInput)) {
      Session session = sessionManager.getOrCreate(msg.channelType(), msg.userId(), agent);
      sessionManager.clearHistory(session.sessionId());
      safeReply(replyVia, msg.chatId(), NEW_SESSION_REPLY, null);
      return;
    }
    List<Message.MediaPart> media = InboundMediaParts.from(msg);
    String sessionId;
    Runnable inference;
    if (msg.chatKind() == ChatKind.P2P) {
      // B2：私聊按「渠道 + 用户 + Agent」维持连续会话——隔离/历史窗口/并发锁全部由既有会话底座承担
      Session session = sessionManager.getOrCreate(msg.channelType(), msg.userId(), agent);
      sessionId = session.sessionId();
      inference =
          () ->
              replyVia.sendReply(
                  msg.chatId(), agentService.process(session, agentInput, media), null);
    } else {
      // B3：群聊每次 @ 为独立无状态问答，不落 sessions 表；渠道前缀让审计可辨（B10）
      sessionId = msg.channelType() + "-group:" + UUID.randomUUID();
      String groupSessionId = sessionId;
      inference =
          () ->
              replyVia.sendReply(
                  msg.chatId(),
                  agentService.processStateless(agent, agentInput, media, groupSessionId),
                  replyTo);
    }
    CountDownLatch done = new CountDownLatch(1);
    // B5/B10：推理在虚拟线程后台跑并落 agent_executions（source = 渠道类型）
    executionService.triggerAsync(
        agent,
        msg.channelType(),
        sessionId,
        () -> {
          try {
            inference.run();
          } catch (RuntimeException e) {
            // B6：失败以可读消息告知用户（不含堆栈），异常继续上抛让执行记录记为失败
            safeReply(replyVia, msg.chatId(), FAILURE_REPLY, replyTo);
            throw e;
          } finally {
            done.countDown();
          }
        });
    scheduleProcessingNotice(done, replyVia, msg.chatId(), replyTo);
  }

  static boolean isNewSessionCommand(String agentInput) {
    return agentInput != null && NEW_SESSION_COMMAND.equals(agentInput.strip());
  }

  /** B8：超过阈值仍未完成时先行告知「处理中」；纯虚拟线程 + CountDownLatch，同步阻塞语义。 */
  private void scheduleProcessingNotice(
      CountDownLatch done, InboundChannelAdapter replyVia, String chatId, String replyTo) {
    Thread.ofVirtual()
        .name("channel-processing-notice")
        .start(
            () -> {
              try {
                if (!done.await(processingNoticeDelay.toMillis(), TimeUnit.MILLISECONDS)) {
                  safeReply(replyVia, chatId, PROCESSING_REPLY, replyTo);
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
  }

  /** 回复失败不影响主流程（发送异常只留日志——用户侧丢一条提示优于整条链路炸掉）。 */
  private void safeReply(
      InboundChannelAdapter replyVia, String chatId, String text, String replyTo) {
    try {
      replyVia.sendReply(chatId, text, replyTo);
    } catch (RuntimeException e) {
      LOG.error("渠道 {} 回复发送失败: {}", sanitize(replyVia.name()), sanitize(e.getMessage()));
    }
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
