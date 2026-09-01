package io.oryxos.channel.feishu;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lark.oapi.core.response.RawResponse;
import com.lark.oapi.core.token.AccessTokenType;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import io.oryxos.core.agent.StreamListener;
import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.ChannelStatus;
import io.oryxos.core.channel.InboundChannelAdapter;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageService;
import io.oryxos.core.channel.OutboundGuard;
import io.oryxos.core.profile.ProfileRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 飞书入站渠道适配器（017 T013）：一实例 = 一个飞书自建应用的一条长连接。
 *
 * <p>长连接免公网回调、免验签（通道自带加密与鉴权，R2）——{@code EventDispatcher.newBuilder("", "")} 两参数按官方要求 填空串。SDK
 * 自动重连（无限重试 + 心跳）白拿，重连回调维护状态供 status 端点呈现。
 *
 * <p>契约规则 A4：start 前置校验凭证已解析、绑定 Agent 存在，失败抛点名异常、渠道不上线（FR-013/SC-008）。 事件 handler
 * 不抛异常（抛出会触发平台重推循环）；归一化返回 empty 的事件（非 @ 群消息等）直接丢弃。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR",
    justification =
        "apiClient/normalizer/sender 在 start() 内初始化（长连接生命周期晚于构造）；事件回调只会在 start 成功后由 SDK 触发，sendReply 有显式空判，不存在未初始化解引用路径。")
public class FeishuChannelAdapter implements InboundChannelAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(FeishuChannelAdapter.class);

  public static final String TYPE = "feishu";
  private static final String API_BASE_URL = "https://open.feishu.cn";
  private static final String BOT_INFO_PATH = "/open-apis/bot/v3/info";
  private static final String BOT_OPEN_ID_FIELD = "open_id";
  private static final int HTTP_OK = 200;
  private static final long READY_TIMEOUT_MS = 15_000;
  private static final long READY_PROBE_TIMEOUT_MS = 50;

  private final ChannelConfig config; // resolved 口径（凭证为真实值，仅存活内存）
  private final ProfileRegistry profileRegistry;
  private final InboundMessageService inboundMessageService;
  private final OutboundGuard guard;

  private volatile com.lark.oapi.Client apiClient;
  private volatile com.lark.oapi.ws.Client wsClient;
  private volatile FeishuMessageSender sender;
  private volatile FeishuReactionManager reactionManager;
  private volatile FeishuCardBuilder cardBuilder;
  private volatile FeishuEventNormalizer normalizer;
  private volatile ChannelStatus.State state = ChannelStatus.State.DISCONNECTED;
  private volatile String lastError;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "协作者均为 Runtime 装配的单例，共享引用正是意图")
  public FeishuChannelAdapter(
      ChannelConfig config,
      ProfileRegistry profileRegistry,
      InboundMessageService inboundMessageService,
      OutboundGuard guard) {
    this.config = config;
    this.profileRegistry = profileRegistry;
    this.inboundMessageService = inboundMessageService;
    this.guard = guard;
  }

  @Override
  public String name() {
    return config.name();
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public String boundAgent() {
    return config.agent();
  }

  @Override
  public synchronized void start() {
    // A4 前置校验：凭证已解析（点名报错）+ 绑定 Agent 存在，失败渠道不上线
    config.validateCredentialsResolved();
    if (profileRegistry.get(config.agent()).isEmpty()) {
      throw new IllegalArgumentException(
          "渠道 " + config.name() + " 绑定的 Agent " + config.agent() + " 不存在");
    }
    guard.check(API_BASE_URL); // 出站白名单在建连前即校验，缺域名尽早点名
    try {
      apiClient = com.lark.oapi.Client.newBuilder(config.appId(), config.appSecret()).build();
      sender =
          new FeishuMessageSender(
              apiClient, guard, API_BASE_URL, FeishuMessageSender.DEFAULT_CHUNK_SIZE);
      reactionManager = new FeishuReactionManager(apiClient);
      cardBuilder = new FeishuCardBuilder();
      normalizer = new FeishuEventNormalizer(config.name(), fetchBotOpenId());
      EventDispatcher dispatcher =
          EventDispatcher.newBuilder("", "") // 长连接免验签：verificationToken/encryptKey 必须空串
              .onP2MessageReceiveV1(
                  new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) {
                      handleEvent(event);
                    }
                  })
              .build();
      com.lark.oapi.ws.Client ws =
          new com.lark.oapi.ws.Client.Builder(config.appId(), config.appSecret())
              .eventHandler(dispatcher)
              .autoReconnect(true)
              .onReconnecting(() -> state = ChannelStatus.State.DISCONNECTED)
              .onReconnected(() -> state = ChannelStatus.State.CONNECTED)
              .build();
      ws.start();
      ws.awaitReady(READY_TIMEOUT_MS);
      wsClient = ws;
      state = ChannelStatus.State.CONNECTED;
      lastError = null;
      LOG.info("飞书渠道 {} 长连接已建立（Agent: {}）", sanitize(config.name()), sanitize(config.agent()));
    } catch (Exception e) {
      state = ChannelStatus.State.ERROR;
      lastError = "长连接建立失败: " + sanitize(e.getMessage());
      throw new IllegalStateException("渠道 " + config.name() + " " + lastError, e);
    }
  }

  @Override
  public synchronized void stop() {
    com.lark.oapi.ws.Client ws = wsClient;
    if (ws != null) {
      try {
        ws.close();
      } catch (RuntimeException e) {
        LOG.warn("飞书渠道 {} 断开时异常: {}", sanitize(config.name()), sanitize(e.getMessage()));
      }
      wsClient = null;
    }
    state = ChannelStatus.State.DISCONNECTED;
  }

  /**
   * 实时状态：以 {@code awaitReady} 主动探测连接就绪（SDK 内部 readyFuture 重连中被重置、就绪后完成）， 不依赖 onReconnected
   * 回调——真机验证发现快速重连路径（如对端 Connection reset 后立即重连）不触发该回调， 仅靠回调会把活连接误报为 DISCONNECTED。探测超时取
   * 50ms，状态查询不阻塞。
   */
  @Override
  public ChannelStatus status() {
    if (state == ChannelStatus.State.ERROR) {
      return ChannelStatus.error(config.name(), TYPE, config.agent(), lastError);
    }
    com.lark.oapi.ws.Client ws = wsClient;
    if (ws == null) {
      return ChannelStatus.ok(
          config.name(), TYPE, config.agent(), ChannelStatus.State.DISCONNECTED);
    }
    try {
      ws.awaitReady(READY_PROBE_TIMEOUT_MS);
      return ChannelStatus.ok(config.name(), TYPE, config.agent(), ChannelStatus.State.CONNECTED);
    } catch (Exception e) {
      return ChannelStatus.ok(
          config.name(), TYPE, config.agent(), ChannelStatus.State.DISCONNECTED);
    }
  }

  @Override
  public void sendReply(String chatId, String text, String replyToMessageId) {
    FeishuMessageSender active = sender;
    if (active == null) {
      throw new IllegalStateException("渠道 " + config.name() + " 未启动，无法发送回复");
    }
    active.send(chatId, text, replyToMessageId);
  }

  @Override
  public StreamListener createStreamListener(InboundMessage msg) {
    // 只有 sender/reactionManager/cardBuilder 都就绪才支持流式
    FeishuMessageSender activeSender = sender;
    FeishuReactionManager activeReaction = reactionManager;
    FeishuCardBuilder activeBuilder = cardBuilder;

    if (activeSender == null || activeReaction == null || activeBuilder == null) {
      LOG.debug("渠道 {} 未就绪，不支持流式", sanitize(config.name()));
      return null;
    }

    // 创建流式监听器
    return new FeishuStreamListener(
        activeSender,
        activeReaction,
        activeBuilder,
        msg.chatId(),
        msg.chatKind() == io.oryxos.core.channel.ChatKind.GROUP ? msg.messageId() : null,
        msg.messageId());
  }

  /** 事件入口：归一化 → 编排；任何异常只留日志——抛出会触发平台重推循环（去重会拦但用户收不到回答）。 */
  private void handleEvent(P2MessageReceiveV1 event) {
    try {
      Optional<InboundMessage> msg = normalizer.normalize(event);
      msg.ifPresent(m -> inboundMessageService.onMessage(m, this));
    } catch (RuntimeException e) {
      LOG.error("飞书渠道 {} 事件处理异常: {}", sanitize(config.name()), sanitize(e.getMessage()));
    }
  }

  /**
   * 获取机器人自身 open_id（供群聊 @ 判定）：GET /open-apis/bot/v3/info（tenant token，SDK 无高层封装走 raw）。 失败降级返回
   * null——归一化按 mentioned_type 判定并 WARN（默认权限下飞书只推 @ 本机器人的群消息，降级安全）。
   */
  private String fetchBotOpenId() {
    try {
      RawResponse resp = apiClient.get(BOT_INFO_PATH, null, AccessTokenType.Tenant);
      if (resp == null || resp.getStatusCode() != HTTP_OK || resp.getBody() == null) {
        LOG.warn(
            "飞书渠道 {} 获取机器人身份失败（HTTP {}），群聊 @ 判定降级",
            sanitize(config.name()),
            resp == null ? -1 : resp.getStatusCode());
        return null;
      }
      JsonElement root = JsonParser.parseString(new String(resp.getBody(), StandardCharsets.UTF_8));
      JsonObject obj = root.getAsJsonObject();
      JsonObject bot =
          obj.has("bot") && obj.get("bot").isJsonObject()
              ? obj.getAsJsonObject("bot")
              : obj.has("data") && obj.get("data").isJsonObject()
                  ? obj.getAsJsonObject("data").getAsJsonObject("bot")
                  : null;
      if (bot == null || bot.get(BOT_OPEN_ID_FIELD) == null) {
        LOG.warn("飞书渠道 {} 机器人身份响应缺 open_id，群聊 @ 判定降级", sanitize(config.name()));
        return null;
      }
      return bot.get(BOT_OPEN_ID_FIELD).getAsString();
    } catch (Exception e) {
      LOG.warn(
          "飞书渠道 {} 获取机器人身份异常（{}），群聊 @ 判定降级", sanitize(config.name()), sanitize(e.getMessage()));
      return null;
    }
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
