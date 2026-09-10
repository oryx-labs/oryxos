package io.oryxos.channel.qq;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.ChannelStatus;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundChannelAdapter;
import io.oryxos.core.channel.InboundMediaRoots;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * QQ 官方机器人入站：一实例 = 一条 Gateway 长连接。
 *
 * <p>凭证：{@code app_id}=AppID，{@code app_secret}=AppSecret（换 access_token）。入站图/文件落盘对齐飞书/Discord。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR",
    justification =
        "gateway/normalizer/sender/tokens/mediaResolver 在 start() 内初始化；sendReply 有显式空判。")
public class QqChannelAdapter implements InboundChannelAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(QqChannelAdapter.class);

  public static final String TYPE = "qq";

  private static final Duration START_TIMEOUT = Duration.ofSeconds(25);
  private static final long RECONNECT_BASE_MS = 2_000L;
  private static final long RECONNECT_MAX_MS = 60_000L;
  private static final int RECONNECT_MAX_SHIFT = 5;
  private static final String MEDIA_DIR_PREFIX = "oryxos-qq-media-";

  private final ChannelConfig config;
  private final ProfileRegistry profileRegistry;
  private final InboundMessageService inboundMessageService;
  private final OutboundGuard guard;

  private final AtomicReference<QqGatewayClient> gatewayRef = new AtomicReference<>();
  private volatile QqAccessTokenClient tokens;
  private volatile QqMessageSender sender;
  private volatile QqEventNormalizer normalizer;
  private volatile QqInboundMediaResolver mediaResolver;
  private volatile QqDisconnectKind lastDisconnectKind = QqDisconnectKind.ABRUPT;
  private volatile ChannelStatus.State state = ChannelStatus.State.DISCONNECTED;
  private volatile String lastError;
  private volatile boolean running;
  private volatile ScheduledExecutorService reconnectScheduler;
  private volatile ScheduledFuture<?> reconnectFuture;
  private int reconnectAttempt;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "协作者均为 Runtime 装配的单例，共享引用正是意图")
  public QqChannelAdapter(
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
    guard.check(QqAccessTokenClient.API_BASE_URL);
    guard.check(QqGatewayClient.GATEWAY_ORIGIN_FOR_GUARD);
    running = true;
    reconnectAttempt = 0;
    cancelReconnectLocked();
    try {
      connectLocked();
      state = ChannelStatus.State.CONNECTED;
      lastError = null;
      LOG.info(
          "QQ 渠道 {} Gateway 已建立（Agent: {}）", sanitize(config.name()), sanitize(config.agent()));
    } catch (Exception e) {
      running = false;
      shutdownReconnectSchedulerLocked();
      state = ChannelStatus.State.ERROR;
      lastError = "Gateway 连接失败: " + sanitize(e.getMessage());
      throw new IllegalStateException("渠道 " + config.name() + " " + lastError, e);
    }
  }

  @Override
  public synchronized void stop() {
    running = false;
    cancelReconnectLocked();
    shutdownReconnectSchedulerLocked();
    QqGatewayClient gateway = gatewayRef.getAndSet(null);
    if (gateway != null) {
      gateway.closeQuietly();
    }
    sender = null;
    normalizer = null;
    mediaResolver = null;
    tokens = null;
    state = ChannelStatus.State.DISCONNECTED;
  }

  @Override
  public ChannelStatus status() {
    if (state == ChannelStatus.State.ERROR) {
      return ChannelStatus.error(config.name(), TYPE, config.agent(), lastError);
    }
    QqGatewayClient gateway = gatewayRef.get();
    if (gateway == null || !gateway.isConnected()) {
      return ChannelStatus.ok(
          config.name(), TYPE, config.agent(), ChannelStatus.State.DISCONNECTED);
    }
    return ChannelStatus.ok(config.name(), TYPE, config.agent(), ChannelStatus.State.CONNECTED);
  }

  @Override
  public void sendReply(String chatId, String text, String replyToMessageId) {
    QqMessageSender active = sender;
    if (active == null) {
      throw new IllegalStateException("渠道 " + config.name() + " 未启动，无法发送回复");
    }
    active.send(chatId, text, replyToMessageId);
  }

  static long reconnectDelayMs(int attempt) {
    return io.oryxos.core.channel.ReconnectBackoff.delayMs(
        attempt, RECONNECT_BASE_MS, RECONNECT_MAX_MS, RECONNECT_MAX_SHIFT);
  }

  private void connectLocked() throws Exception {
    gatewayRef.set(connect());
  }

  private QqGatewayClient connect() throws Exception {
    ensureOutboundStack();
    QqGatewayClient client =
        new QqGatewayClient(tokens, guard, this::handleDispatch, this::handleDisconnected);
    client.connect(START_TIMEOUT);
    return client;
  }

  private void ensureOutboundStack() {
    if (tokens == null) {
      tokens = new QqAccessTokenClient(guard, config.appId(), config.appSecret());
    }
    if (normalizer == null) {
      normalizer = new QqEventNormalizer(config.name());
    }
    if (sender == null) {
      sender = new QqMessageSender(guard, tokens);
    }
    if (mediaResolver == null) {
      QqAccessTokenClient tokenClient = tokens;
      mediaResolver =
          new QqInboundMediaResolver(
              tokenClient::authorizationHeader,
              InboundMediaRoots.forChannel(config.name(), MEDIA_DIR_PREFIX),
              config.name());
    }
  }

  private void handleDisconnected(QqDisconnectKind kind) {
    boolean shouldReconnect;
    synchronized (this) {
      if (!running || state == ChannelStatus.State.ERROR) {
        return;
      }
      lastDisconnectKind = kind == null ? QqDisconnectKind.ABRUPT : kind;
      if (lastDisconnectKind == QqDisconnectKind.GRACEFUL) {
        reconnectAttempt = 0;
      }
      gatewayRef.set(null);
      state = ChannelStatus.State.DISCONNECTED;
      shouldReconnect = true;
    }
    if (shouldReconnect) {
      if (lastDisconnectKind == QqDisconnectKind.GRACEFUL) {
        LOG.info("QQ 渠道 {} Gateway 服务端要求重连，立即重连", sanitize(config.name()));
      } else {
        LOG.warn("QQ 渠道 {} Gateway 断开，将自动重连", sanitize(config.name()));
      }
      scheduleReconnect();
    }
  }

  private void scheduleReconnect() {
    synchronized (this) {
      if (!running || reconnectFuture != null) {
        return;
      }
      long delayMs =
          lastDisconnectKind == QqDisconnectKind.GRACEFUL ? 0L : reconnectDelayMs(reconnectAttempt);
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
    }
    QqGatewayClient client;
    try {
      client = connect();
    } catch (Exception e) {
      synchronized (this) {
        reconnectAttempt++;
        lastError = "Gateway 重连失败: " + sanitize(e.getMessage());
        LOG.warn(
            "QQ 渠道 {} 重连失败（第 {} 次）: {}",
            sanitize(config.name()),
            reconnectAttempt,
            sanitize(lastError));
      }
      scheduleReconnect();
      return;
    }
    synchronized (this) {
      if (!running) {
        client.closeQuietly();
        return;
      }
      gatewayRef.set(client);
      reconnectAttempt = 0;
      state = ChannelStatus.State.CONNECTED;
      lastError = null;
      LOG.info("QQ 渠道 {} Gateway 已恢复", sanitize(config.name()));
    }
  }

  private ScheduledExecutorService reconnectScheduler() {
    ScheduledExecutorService scheduler = reconnectScheduler;
    if (scheduler == null) {
      ScheduledThreadPoolExecutor pool =
          new ScheduledThreadPoolExecutor(
              1,
              r -> {
                Thread t = new Thread(r, "qq-reconnect-" + sanitize(config.name()));
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

  private void handleDispatch(String eventName, JsonNode data) {
    try {
      if (QqEventNormalizer.EVENT_C2C.equals(eventName)
          || QqEventNormalizer.EVENT_GROUP_AT.equals(eventName)) {
        LOG.info(
            "QQ 渠道 {} 收到 {}（{}）",
            sanitize(config.name()),
            sanitize(eventName),
            summarizeAttachments(data));
      }
      Optional<InboundMessage> msg = normalizer.normalize(eventName, data);
      msg.ifPresent(this::dispatchClaimed);
    } catch (RuntimeException e) {
      LOG.error("QQ 渠道 {} 事件处理异常: {}", sanitize(config.name()), sanitize(e.getMessage()));
    }
  }

  /** 仅摘要 content_type / 是否有 url·wav，不落完整 URL。 */
  static String summarizeAttachments(JsonNode data) {
    if (data == null || !data.isObject()) {
      return "attachments=0";
    }
    JsonNode arr = data.path("attachments");
    if (!arr.isArray() || arr.isEmpty()) {
      return "attachments=0";
    }
    StringBuilder sb = new StringBuilder("attachments=").append(arr.size()).append('[');
    for (int i = 0; i < arr.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      JsonNode a = arr.get(i);
      String ct = a.path("content_type").asText("?");
      String fn = a.path("filename").asText("");
      boolean hasUrl = a.hasNonNull("url") && !a.path("url").asText("").isBlank();
      boolean hasWav =
          a.hasNonNull("voice_wav_url") && !a.path("voice_wav_url").asText("").isBlank();
      sb.append(sanitize(ct));
      if (!fn.isBlank()) {
        int dot = fn.lastIndexOf('.');
        if (dot >= 0 && dot < fn.length() - 1) {
          sb.append(sanitize(asciiLower(fn.substring(dot))));
        }
      }
      sb.append(hasUrl ? "+url" : "-url");
      if (hasWav) {
        sb.append("+wav");
      }
    }
    sb.append(']');
    return sb.toString();
  }

  private void dispatchClaimed(InboundMessage m) {
    if (!inboundMessageService.tryClaim(m.channelName(), m.messageId())) {
      LOG.info("渠道 {} 重复事件已忽略: {}", sanitize(m.channelName()), sanitize(m.messageId()));
      return;
    }
    String replyTo = m.chatKind() == ChatKind.GROUP ? m.messageId() : null;
    CountDownLatch slowWork = null;
    if (QqInboundMediaResolver.hasDownloadableMedia(m)) {
      slowWork = inboundMessageService.beginSlowWork(this, m.chatId(), replyTo);
    }
    try {
      QqInboundMediaResolver resolver = mediaResolver;
      InboundMessage enriched = resolver == null ? m : resolver.resolve(m);
      if (slowWork != null) {
        inboundMessageService.onClaimedMessage(enriched, this, slowWork);
      } else {
        inboundMessageService.onClaimedMessage(enriched, this);
      }
    } catch (RuntimeException e) {
      if (slowWork != null) {
        slowWork.countDown();
      }
      throw e;
    }
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }

  private static String asciiLower(String value) {
    char[] chars = value.toCharArray();
    for (int i = 0; i < chars.length; i++) {
      char c = chars[i];
      if (c >= 'A' && c <= 'Z') {
        chars[i] = (char) (c + ('a' - 'A'));
      }
    }
    return new String(chars);
  }
}
