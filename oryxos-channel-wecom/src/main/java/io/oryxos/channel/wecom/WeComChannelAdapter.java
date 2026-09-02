package io.oryxos.channel.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.ChannelStatus;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundChannelAdapter;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageService;
import io.oryxos.core.channel.OutboundGuard;
import io.oryxos.core.profile.ProfileRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 企微智能机器人入站适配器：一实例 = 一个 Bot 的一条长连接（对称飞书 017）。
 *
 * <p>凭证映射：{@code app_id} = BotID，{@code app_secret} = 长连接 Secret。长连接免公网回调、免 EncodingAESKey。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR",
    justification = "ws/normalizer/sender 在 start() 内初始化；sendReply 有显式空判。")
public class WeComChannelAdapter implements InboundChannelAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(WeComChannelAdapter.class);

  public static final String TYPE = "wecom";

  /** OutboundGuard / 白名单用 https 形式主机名（与 wss 同域）。 */
  static final String OUTBOUND_URL = "https://openws.work.weixin.qq.com";

  private static final String CMD_AIBOT_MSG_CALLBACK = "aibot_msg_callback";

  private static final Duration START_TIMEOUT = Duration.ofSeconds(20);
  private static final long RECONNECT_BASE_MS = 2_000L;
  private static final long RECONNECT_MAX_MS = 60_000L;
  private static final int RECONNECT_MAX_SHIFT = 5;

  private final ChannelConfig config;
  private final ProfileRegistry profileRegistry;
  private final InboundMessageService inboundMessageService;
  private final OutboundGuard guard;

  private final AtomicReference<WeComWsClient> wsRef = new AtomicReference<>();
  private final AtomicReference<Consumer<ObjectNode>> transportRef = new AtomicReference<>();
  private volatile WeComMessageSender sender;
  private volatile WeComEventNormalizer normalizer;
  private volatile ChannelStatus.State state = ChannelStatus.State.DISCONNECTED;
  private volatile String lastError;
  private volatile boolean running;
  private volatile ScheduledExecutorService reconnectScheduler;
  private volatile ScheduledFuture<?> reconnectFuture;
  private int reconnectAttempt;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "协作者均为 Runtime 装配的单例，共享引用正是意图")
  public WeComChannelAdapter(
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
    config.validateCredentialsResolved();
    if (profileRegistry.get(config.agent()).isEmpty()) {
      throw new IllegalArgumentException(
          "渠道 " + config.name() + " 绑定的 Agent " + config.agent() + " 不存在");
    }
    guard.check(OUTBOUND_URL);
    running = true;
    reconnectAttempt = 0;
    cancelReconnectLocked();
    try {
      connectLocked();
      state = ChannelStatus.State.CONNECTED;
      lastError = null;
      LOG.info("企微渠道 {} 长连接已建立（Agent: {}）", sanitize(config.name()), sanitize(config.agent()));
    } catch (Exception e) {
      running = false;
      shutdownReconnectSchedulerLocked();
      state = ChannelStatus.State.ERROR;
      lastError = "长连接建立失败: " + sanitize(e.getMessage());
      throw new IllegalStateException("渠道 " + config.name() + " " + lastError, e);
    }
  }

  @Override
  public synchronized void stop() {
    running = false;
    cancelReconnectLocked();
    shutdownReconnectSchedulerLocked();
    WeComWsClient ws = wsRef.getAndSet(null);
    if (ws != null) {
      ws.closeQuietly();
    }
    transportRef.set(null);
    sender = null;
    normalizer = null;
    state = ChannelStatus.State.DISCONNECTED;
  }

  @Override
  public ChannelStatus status() {
    if (state == ChannelStatus.State.ERROR) {
      return ChannelStatus.error(config.name(), TYPE, config.agent(), lastError);
    }
    WeComWsClient ws = wsRef.get();
    if (ws == null || !ws.isSubscribed()) {
      return ChannelStatus.ok(
          config.name(), TYPE, config.agent(), ChannelStatus.State.DISCONNECTED);
    }
    return ChannelStatus.ok(config.name(), TYPE, config.agent(), ChannelStatus.State.CONNECTED);
  }

  @Override
  public void sendReply(String chatId, String text, String replyToMessageId) {
    WeComMessageSender active = sender;
    if (active == null) {
      throw new IllegalStateException("渠道 " + config.name() + " 未启动，无法发送回复");
    }
    active.send(chatId, text, replyToMessageId);
  }

  /** 供单测校验退避间隔，不触网。 */
  static long reconnectDelayMs(int attempt) {
    int capped = Math.min(Math.max(attempt, 0), RECONNECT_MAX_SHIFT);
    return Math.min(RECONNECT_BASE_MS * (1L << capped), RECONNECT_MAX_MS);
  }

  private void connectLocked() throws Exception {
    ensureOutboundStack();
    WeComWsClient client =
        new WeComWsClient(
            config.appId(),
            config.appSecret(),
            WeComWsClient.DEFAULT_WS_URL,
            this::handleFrame,
            this::handleDisconnected);
    transportRef.set(client::sendJson);
    client.connectAndSubscribe(START_TIMEOUT);
    wsRef.set(client);
  }

  /** 懒初始化出站组件；重连复用同一 {@link WeComMessageSender} 以保留 chatTypes 映射。 */
  private void ensureOutboundStack() {
    if (normalizer == null) {
      normalizer = new WeComEventNormalizer(config.name());
    }
    if (sender == null) {
      sender =
          new WeComMessageSender(
              frame -> {
                Consumer<ObjectNode> transport = transportRef.get();
                if (transport == null) {
                  throw new IllegalStateException("企微长连接未建立，无法发送");
                }
                transport.accept(frame);
              },
              guard,
              OUTBOUND_URL,
              WeComMessageSender.DEFAULT_CHUNK_SIZE);
    }
  }

  private void handleDisconnected() {
    boolean shouldReconnect;
    synchronized (this) {
      if (!running || state == ChannelStatus.State.ERROR) {
        return;
      }
      wsRef.set(null);
      state = ChannelStatus.State.DISCONNECTED;
      shouldReconnect = true;
    }
    if (shouldReconnect) {
      LOG.warn("企微渠道 {} 长连接断开，将自动重连", sanitize(config.name()));
      scheduleReconnect();
    }
  }

  private void scheduleReconnect() {
    synchronized (this) {
      if (!running || reconnectFuture != null) {
        return;
      }
      long delayMs = reconnectDelayMs(reconnectAttempt);
      reconnectFuture =
          reconnectScheduler().schedule(this::attemptReconnect, delayMs, TimeUnit.MILLISECONDS);
    }
  }

  private void attemptReconnect() {
    synchronized (this) {
      reconnectFuture = null;
      if (!running) {
        return;
      }
      try {
        connectLocked();
        reconnectAttempt = 0;
        state = ChannelStatus.State.CONNECTED;
        lastError = null;
        LOG.info("企微渠道 {} 长连接已恢复", sanitize(config.name()));
      } catch (Exception e) {
        reconnectAttempt++;
        lastError = "长连接重连失败: " + sanitize(e.getMessage());
        LOG.warn(
            "企微渠道 {} 重连失败（第 {} 次）: {}",
            sanitize(config.name()),
            reconnectAttempt,
            sanitize(lastError));
        scheduleReconnect();
      }
    }
  }

  private ScheduledExecutorService reconnectScheduler() {
    ScheduledExecutorService scheduler = reconnectScheduler;
    if (scheduler == null) {
      ScheduledThreadPoolExecutor pool =
          new ScheduledThreadPoolExecutor(
              1,
              r -> {
                Thread t = new Thread(r, "wecom-reconnect-" + sanitize(config.name()));
                t.setDaemon(true);
                return t;
              });
      pool.setRemoveOnCancelPolicy(true);
      reconnectScheduler = pool;
      return pool;
    }
    return scheduler;
  }

  private void cancelReconnectLocked() {
    ScheduledFuture<?> pending = reconnectFuture;
    if (pending != null) {
      pending.cancel(false);
      reconnectFuture = null;
    }
  }

  private void shutdownReconnectSchedulerLocked() {
    cancelReconnectLocked();
    ScheduledExecutorService scheduler = reconnectScheduler;
    if (scheduler != null) {
      scheduler.shutdownNow();
      reconnectScheduler = null;
    }
  }

  private void handleFrame(JsonNode root) {
    String cmd = root.path("cmd").asText("");
    if (!CMD_AIBOT_MSG_CALLBACK.equals(cmd)) {
      return;
    }
    JsonNode body = root.get("body");
    try {
      Optional<InboundMessage> msg = normalizer.normalize(body);
      msg.ifPresent(
          m -> {
            sender.rememberChatType(m.chatId(), WeComEventNormalizer.chatTypeCode(body));
            dispatchClaimed(m);
          });
    } catch (RuntimeException e) {
      LOG.error("企微渠道 {} 事件处理异常: {}", sanitize(config.name()), sanitize(e.getMessage()));
    }
  }

  /** 有图时即时「处理中」（识图常低于默认 15s 阈值）；文本仍走 onMessage 内延迟提示。 */
  private void dispatchClaimed(InboundMessage m) {
    if (!inboundMessageService.tryClaim(m.channelName(), m.messageId())) {
      LOG.info("渠道 {} 重复事件已忽略: {}", sanitize(m.channelName()), sanitize(m.messageId()));
      return;
    }
    if (!hasImage(m)) {
      inboundMessageService.onClaimedMessage(m, this);
      return;
    }
    String replyTo = m.chatKind() == ChatKind.GROUP ? m.messageId() : null;
    CountDownLatch slowWork = inboundMessageService.beginSlowWork(this, m.chatId(), replyTo);
    try {
      inboundMessageService.onClaimedMessage(m, this, slowWork);
    } catch (RuntimeException e) {
      slowWork.countDown();
      throw e;
    }
  }

  private static boolean hasImage(InboundMessage message) {
    for (InboundAttachment attachment : message.attachments()) {
      if (InboundAttachment.TYPE_IMAGE.equals(attachment.type())) {
        return true;
      }
    }
    return false;
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
