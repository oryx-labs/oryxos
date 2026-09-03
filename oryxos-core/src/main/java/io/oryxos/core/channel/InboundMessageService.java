package io.oryxos.core.channel;

import io.oryxos.core.agent.AgentExecutionService;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.agent.InterruptManager;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.session.Message;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
  private static final String STOP_COMMAND = "/stop";

  static final String UNSUPPORTED_TYPE_REPLY = "当前仅支持文本提问，请用文字描述你的问题。";
  static final String AGENT_UNAVAILABLE_REPLY = "Agent 暂不可用（未找到绑定的 Agent），请联系管理员。";
  static final String FAILURE_REPLY = "抱歉，这次处理失败了，请稍后重试或联系管理员。";
  static final String PROCESSING_REPLY = "已收到，正在处理中，请稍候…";
  static final String NEW_SESSION_REPLY = "已开启新会话，之前的对话上下文已清空。";
  static final String STOP_REPLY = "已发送停止信号，正在执行的任务将在下一轮停止。";
  static final String STOP_NO_SESSION_REPLY = "当前没有正在执行的任务。";
  private static final String NEW_SESSION_COMMAND = "/new";

  /** 飞书等可能对同一意图连推多条不同 message_id；短窗内只确认一次。 */
  private static final long NEW_SESSION_ACK_COALESCE_MS = 5_000;

  /** 「处理中」提示合并窗：下载+推理链路很长时避免重推/重复调度刷屏。 */
  private static final long PROCESSING_NOTICE_COALESCE_MS = 30_000;

  private final AgentService agentService;
  private final SessionManager sessionManager;
  private final ProfileRegistry profileRegistry;
  private final AgentExecutionService executionService;
  private final MessageDeduplicator deduplicator;
  private final InboundMediaEnricher mediaEnricher;
  private final Duration processingNoticeDelay;
  private final Map<String, Long> recentNewSessionAckMs = new ConcurrentHashMap<>();
  private final Map<String, Long> recentProcessingNoticeMs = new ConcurrentHashMap<>();
  private InterruptManager interruptManager;

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

  /**
   * 设置中断管理器（可选）。
   *
   * @param interruptManager 中断管理器
   */
  public void setInterruptManager(InterruptManager interruptManager) {
    this.interruptManager = interruptManager;
  }

  /** 在昂贵预处理（飞书入站图下载等）前占用 message_id。返回 false 表示重复事件，调用方应直接丢弃且勿再下载。 */
  public boolean tryClaim(String channelName, String messageId) {
    return deduplicator.markIfFirst(channelName + DEDUP_KEY_SEPARATOR + messageId);
  }

  /**
   * 昂贵预处理（如下载图片）开始前立刻发 B8「处理中」（仍受合并窗约束）。下载+识图常在默认阈值（15s）内结束，若仍走延迟计时，用户会感到「识别完 才提示」。返回的 latch
   * 必须在整条入站链路结束时 {@code countDown}，并交给 {@link #onClaimedMessage(InboundMessage,
   * InboundChannelAdapter, CountDownLatch)}，避免推理阶段再开一个提示。
   */
  public CountDownLatch beginSlowWork(
      InboundChannelAdapter replyVia, String chatId, String replyToMessageId) {
    CountDownLatch done = new CountDownLatch(1);
    if (shouldSendProcessingNotice(chatId)) {
      safeReply(replyVia, chatId, PROCESSING_REPLY, replyToMessageId);
    }
    return done;
  }

  /**
   * 归一化消息唯一入口：确认线程内快速返回（去重 + 提交），推理与回发在虚拟线程（B5）。
   *
   * @param msg 归一化入站消息（群聊必须已在适配器层过滤为 @ 机器人的消息）
   * @param replyVia 回复通道（即产生本消息的适配器）
   */
  public void onMessage(InboundMessage msg, InboundChannelAdapter replyVia) {
    onMessage(msg, replyVia, true, null);
  }

  /** 调用方已通过 {@link #tryClaim} 占用 message_id 时使用（避免下载完成后二次去重把合法首条丢掉）。 */
  public void onClaimedMessage(InboundMessage msg, InboundChannelAdapter replyVia) {
    onMessage(msg, replyVia, false, null);
  }

  /**
   * 同 {@link #onClaimedMessage(InboundMessage, InboundChannelAdapter)}，复用预处理阶段已启动的
   * latch（不再二次调度「处理中」）。
   */
  public void onClaimedMessage(
      InboundMessage msg, InboundChannelAdapter replyVia, CountDownLatch preprocessingDone) {
    onMessage(msg, replyVia, false, preprocessingDone);
  }

  private void onMessage(
      InboundMessage msg,
      InboundChannelAdapter replyVia,
      boolean claimMessageId,
      CountDownLatch preprocessingDone) {
    // B1：同一渠道同一 message_id 只处理一次（平台超时重推场景用户只收到一条回答）
    if (claimMessageId
        && !deduplicator.markIfFirst(msg.channelName() + DEDUP_KEY_SEPARATOR + msg.messageId())) {
      LOG.info("渠道 {} 重复事件已忽略: {}", sanitize(msg.channelName()), sanitize(msg.messageId()));
      return;
    }
    // B4：群聊回复引用原消息使问答可对应；私聊直发
    String replyTo = msg.chatKind() == ChatKind.GROUP ? msg.messageId() : null;
    String agentInput = mediaEnricher.toAgentInput(msg);
    if (finishWithoutInference(msg, replyVia, preprocessingDone, replyTo, agentInput)) {
      return;
    }
    String agent = replyVia.boundAgent();
    List<Message.MediaPart> media = InboundMediaParts.from(msg);
    InferenceJob job = buildInference(msg, replyVia, agent, agentInput, media, replyTo);
    CountDownLatch done = preprocessingDone != null ? preprocessingDone : new CountDownLatch(1);
    // B5/B10：推理在虚拟线程后台跑并落 agent_executions（source = 渠道类型）
    executionService.triggerAsync(
        agent,
        msg.channelType(),
        job.sessionId(),
        () -> {
          try {
            job.inference().run();
          } catch (RuntimeException e) {
            // B6：失败以可读消息告知用户（不含堆栈），异常继续上抛让执行记录记为失败
            if (!job.streamed()) {
              safeReply(replyVia, msg.chatId(), FAILURE_REPLY, replyTo);
            }
            throw e;
          } finally {
            done.countDown();
          }
        });
    // 进度流已发「思考中」卡片时不再发延迟「处理中」文本，避免双提示
    if (preprocessingDone == null && !job.streamed()) {
      scheduleProcessingNotice(done, replyVia, msg.chatId(), replyTo);
    }
  }

  /** B7/B9/私聊 /new：不进推理时释放预处理 latch 并回即时答复。 */
  private boolean finishWithoutInference(
      InboundMessage msg,
      InboundChannelAdapter replyVia,
      CountDownLatch preprocessingDone,
      String replyTo,
      String agentInput) {
    // 处理 /stop 命令
    if (msg.textual() && isStopCommand(agentInput)) {
      release(preprocessingDone);
      handleStopCommand(msg, replyVia, replyTo);
      return true;
    }
    // B7：不可处理的消息（非文本且无附件）回能力说明，不进推理、不落执行记录
    if (!msg.processable() || agentInput.isBlank()) {
      release(preprocessingDone);
      safeReply(replyVia, msg.chatId(), UNSUPPORTED_TYPE_REPLY, replyTo);
      return true;
    }
    String agent = replyVia.boundAgent();
    // B9：绑定 Agent 不存在（底座无停用态，不存在即不可用）——可读回复 + 失败执行留痕
    if (profileRegistry.get(agent).isEmpty()) {
      release(preprocessingDone);
      safeReply(replyVia, msg.chatId(), AGENT_UNAVAILABLE_REPLY, replyTo);
      executionService.triggerAsync(
          agent,
          msg.channelType(),
          null,
          () -> {
            throw new IllegalStateException(
                "渠道 " + msg.channelName() + " 绑定的 Agent " + agent + " 不存在");
          });
      return true;
    }
    // 私聊 /new：清空固定三元组会话历史（飞书等 IM 无独立「新会话」键）
    if (msg.chatKind() == ChatKind.P2P && msg.textual() && isNewSessionCommand(agentInput)) {
      release(preprocessingDone);
      Session session = sessionManager.getOrCreate(msg.channelType(), msg.userId(), agent);
      sessionManager.clearHistory(session.sessionId());
      if (shouldAckNewSession(session.sessionId())) {
        safeReply(replyVia, msg.chatId(), NEW_SESSION_REPLY, null);
      }
      return true;
    }
    return false;
  }

  private void handleStopCommand(
      InboundMessage msg, InboundChannelAdapter replyVia, String replyTo) {
    if (interruptManager == null) {
      safeReply(replyVia, msg.chatId(), STOP_NO_SESSION_REPLY, replyTo);
      return;
    }
    String agent = replyVia.boundAgent();
    if (msg.chatKind() == ChatKind.P2P) {
      Session session = sessionManager.getOrCreate(msg.channelType(), msg.userId(), agent);
      interruptManager.interrupt(session.sessionId());
      safeReply(replyVia, msg.chatId(), STOP_REPLY, replyTo);
    } else {
      // 群聊无状态，无法中断
      safeReply(replyVia, msg.chatId(), STOP_NO_SESSION_REPLY, replyTo);
    }
  }

  private boolean isStopCommand(String text) {
    return STOP_COMMAND.equalsIgnoreCase(text.trim());
  }

  private InferenceJob buildInference(
      InboundMessage msg,
      InboundChannelAdapter replyVia,
      String agent,
      String agentInput,
      List<Message.MediaPart> media,
      String replyTo) {
    if (msg.chatKind() == ChatKind.P2P) {
      // B2：私聊按「渠道 + 用户 + Agent」维持连续会话——隔离/历史窗口/并发锁全部由既有会话底座承担
      Session session = sessionManager.getOrCreate(msg.channelType(), msg.userId(), agent);
      return progressOrPlain(
          replyVia,
          msg.chatId(),
          replyTo,
          session.sessionId(),
          () -> agentService.process(session, agentInput, media),
          (stream) -> agentService.process(session, agentInput, media, stream));
    }
    // B3：群聊每次 @ 为独立无状态问答，不落 sessions 表；渠道前缀让审计可辨（B10）
    String groupSessionId = msg.channelType() + "-group:" + UUID.randomUUID();
    return progressOrPlain(
        replyVia,
        msg.chatId(),
        replyTo,
        groupSessionId,
        () -> agentService.processStateless(agent, agentInput, media, groupSessionId),
        (stream) ->
            agentService.processStateless(agent, agentInput, media, groupSessionId, stream));
  }

  private InferenceJob progressOrPlain(
      InboundChannelAdapter replyVia,
      String chatId,
      String replyTo,
      String sessionId,
      java.util.function.Supplier<String> plainInference,
      java.util.function.Function<io.oryxos.core.agent.StreamListener, String> streamedInference) {
    var streamOpt = replyVia.openProgressStream(chatId, replyTo);
    if (streamOpt.isEmpty()) {
      return new InferenceJob(
          sessionId, () -> replyVia.sendReply(chatId, plainInference.get(), replyTo), false);
    }
    InboundProgressStream stream = streamOpt.get();
    try {
      stream.start();
    } catch (RuntimeException e) {
      LOG.warn("渠道 {} 进度流启动失败，降级整段回复: {}", sanitize(replyVia.name()), sanitize(e.getMessage()));
      return new InferenceJob(
          sessionId, () -> replyVia.sendReply(chatId, plainInference.get(), replyTo), false);
    }
    return new InferenceJob(
        sessionId,
        () -> {
          try {
            String reply = streamedInference.apply(stream);
            stream.finish(reply);
          } catch (RuntimeException e) {
            try {
              stream.fail(FAILURE_REPLY);
            } catch (RuntimeException failSend) {
              LOG.warn(
                  "渠道 {} 进度流失败态更新失败: {}",
                  sanitize(replyVia.name()),
                  sanitize(failSend.getMessage()));
              safeReply(replyVia, chatId, FAILURE_REPLY, replyTo);
            }
            throw e;
          }
        },
        true);
  }

  private static void release(CountDownLatch latch) {
    if (latch != null) {
      latch.countDown();
    }
  }

  private record InferenceJob(String sessionId, Runnable inference, boolean streamed) {}

  static boolean isNewSessionCommand(String agentInput) {
    return agentInput != null && NEW_SESSION_COMMAND.equals(agentInput.strip());
  }

  /** 首次或超过合并窗则确认；窗内重复 {@code /new}（不同 message_id）仍清历史，但不再回第二条确认。 */
  private boolean shouldAckNewSession(String sessionId) {
    long now = System.currentTimeMillis();
    Long prev = recentNewSessionAckMs.put(sessionId, now);
    return prev == null || now - prev >= NEW_SESSION_ACK_COALESCE_MS;
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
                  if (shouldSendProcessingNotice(chatId)) {
                    safeReply(replyVia, chatId, PROCESSING_REPLY, replyTo);
                  }
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
  }

  private boolean shouldSendProcessingNotice(String chatId) {
    long now = System.currentTimeMillis();
    Long prev = recentProcessingNoticeMs.put(chatId, now);
    return prev == null || now - prev >= PROCESSING_NOTICE_COALESCE_MS;
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
