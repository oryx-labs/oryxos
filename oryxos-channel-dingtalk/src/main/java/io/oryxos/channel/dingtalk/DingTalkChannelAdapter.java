package io.oryxos.channel.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.ChannelStatus;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundChannelAdapter;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageService;
import io.oryxos.core.channel.OutboundGuard;
import io.oryxos.core.profile.ProfileRegistry;
import java.nio.file.Path;
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
 * 钉钉机器人入站适配器：一实例 = 一个应用的一条 Stream 长连接（对称飞书/企微）。
 *
 * <p>凭证映射：{@code app_id} = ClientId，{@code app_secret} = ClientSecret。断线后自动重连（对齐企微 #319）。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR",
    justification = "stream/normalizer/sender 在 start() 内初始化；sendReply 有显式空判。")
public class DingTalkChannelAdapter implements InboundChannelAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(DingTalkChannelAdapter.class);

  public static final String TYPE = "dingtalk";

  private static final Duration START_TIMEOUT = Duration.ofSeconds(20);
  private static final long RECONNECT_BASE_MS = 2_000L;
  private static final long RECONNECT_MAX_MS = 60_000L;
  private static final int RECONNECT_MAX_SHIFT = 5;
  private static final String MEDIA_DIR_PREFIX = "oryxos-dingtalk-media-";

  private final ChannelConfig config;
  private final ProfileRegistry profileRegistry;
  private final InboundMessageService inboundMessageService;
  private final OutboundGuard guard;

  private final AtomicReference<DingTalkStreamClient> streamRef = new AtomicReference<>();
  private volatile DingTalkMessageSender sender;
  private volatile DingTalkEventNormalizer normalizer;
  private volatile DingTalkInboundImageResolver imageResolver;
  private volatile DingTalkDisconnectKind lastDisconnectKind = DingTalkDisconnectKind.ABRUPT;
  private volatile ChannelStatus.State state = ChannelStatus.State.DISCONNECTED;
  private volatile String lastError;
  private volatile boolean running;
  private volatile ScheduledExecutorService reconnectScheduler;
  private volatile ScheduledFuture<?> reconnectFuture;
  private int reconnectAttempt;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "协作者均为 Runtime 装配的单例，共享引用正是意图")
  public DingTalkChannelAdapter(
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
    guard.check(DingTalkStreamClient.API_BASE_URL);
    guard.check(DingTalkMessageSender.SESSION_WEBHOOK_PREFIX);
    running = true;
    reconnectAttempt = 0;
    cancelReconnectLocked();
    try {
      connectLocked();
      state = ChannelStatus.State.CONNECTED;
      lastError = null;
      LOG.info("钉钉渠道 {} Stream 已建立（Agent: {}）", sanitize(config.name()), sanitize(config.agent()));
    } catch (Exception e) {
      running = false;
      shutdownReconnectSchedulerLocked();
      state = ChannelStatus.State.ERROR;
      lastError = "Stream 连接失败: " + sanitize(e.getMessage());
      throw new IllegalStateException("渠道 " + config.name() + " " + lastError, e);
    }
  }

  @Override
  public synchronized void stop() {
    running = false;
    cancelReconnectLocked();
    shutdownReconnectSchedulerLocked();
    DingTalkStreamClient stream = streamRef.getAndSet(null);
    if (stream != null) {
      stream.closeQuietly();
    }
    sender = null;
    normalizer = null;
    imageResolver = null;
    state = ChannelStatus.State.DISCONNECTED;
  }

  @Override
  public ChannelStatus status() {
    if (state == ChannelStatus.State.ERROR) {
      return ChannelStatus.error(config.name(), TYPE, config.agent(), lastError);
    }
    DingTalkStreamClient stream = streamRef.get();
    if (stream == null || !stream.isConnected()) {
      return ChannelStatus.ok(
          config.name(), TYPE, config.agent(), ChannelStatus.State.DISCONNECTED);
    }
    return ChannelStatus.ok(config.name(), TYPE, config.agent(), ChannelStatus.State.CONNECTED);
  }

  @Override
  public void sendReply(String chatId, String text, String replyToMessageId) {
    DingTalkMessageSender active = sender;
    if (active == null) {
      throw new IllegalStateException("渠道 " + config.name() + " 未启动，无法发送回复");
    }
    active.send(chatId, text, replyToMessageId);
  }

  @Override
  public java.util.Optional<io.oryxos.core.channel.InboundProgressStream> openProgressStream(
      String chatId, String replyToMessageId) {
    DingTalkMessageSender active = sender;
    if (active == null) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.of(new DingTalkProgressStream(active, chatId, replyToMessageId));
  }

  /** 供单测校验退避间隔，不触网。 */
  static long reconnectDelayMs(int attempt) {
    return io.oryxos.core.channel.ReconnectBackoff.delayMs(
        attempt, RECONNECT_BASE_MS, RECONNECT_MAX_MS, RECONNECT_MAX_SHIFT);
  }

  private void connectLocked() throws Exception {
    streamRef.set(connect());
  }

  /** 建连并返回客户端；不写入 {@link #streamRef}——由调用方在持锁处决定是否接管（重连期间可能已被 stop）。 */
  private DingTalkStreamClient connect() throws Exception {
    ensureOutboundStack();
    DingTalkStreamClient client =
        new DingTalkStreamClient(
            config.appId(),
            config.appSecret(),
            guard,
            this::handleBotMessage,
            this::handleDisconnected);
    client.connect(START_TIMEOUT);
    return client;
  }

  /** 懒初始化出站/入站组件；重连复用同一 {@link DingTalkMessageSender} 以保留 sessionWebhook 映射。 */
  private void ensureOutboundStack() {
    if (normalizer == null) {
      normalizer = new DingTalkEventNormalizer(config.name());
    }
    if (sender == null) {
      sender = new DingTalkMessageSender(guard, DingTalkMessageSender.DEFAULT_CHUNK_SIZE);
    }
    if (imageResolver == null) {
      // robotCode：企业内部应用机器人通常等于 ClientId（app_id）
      imageResolver =
          new DingTalkInboundImageResolver(
              guard,
              config.appId(),
              config.appSecret(),
              config.appId(),
              createMediaRoot(),
              config.name());
    }
  }

  private void handleDisconnected(DingTalkDisconnectKind kind) {
    boolean shouldReconnect;
    synchronized (this) {
      if (!running || state == ChannelStatus.State.ERROR) {
        return;
      }
      lastDisconnectKind = kind == null ? DingTalkDisconnectKind.ABRUPT : kind;
      if (lastDisconnectKind == DingTalkDisconnectKind.GRACEFUL) {
        reconnectAttempt = 0;
      }
      streamRef.set(null);
      state = ChannelStatus.State.DISCONNECTED;
      shouldReconnect = true;
    }
    if (shouldReconnect) {
      if (lastDisconnectKind == DingTalkDisconnectKind.GRACEFUL) {
        LOG.info("钉钉渠道 {} Stream 服务端轮换断开，立即重连", sanitize(config.name()));
      } else {
        LOG.warn("钉钉渠道 {} Stream 断开，将自动重连", sanitize(config.name()));
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
          lastDisconnectKind == DingTalkDisconnectKind.GRACEFUL
              ? 0L
              : reconnectDelayMs(reconnectAttempt);
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
    // 建连放锁外：Stream connect 可能长时间阻塞，锁内执行会把 stop()/管理端停用卡住
    DingTalkStreamClient client;
    try {
      client = connect();
    } catch (Exception e) {
      synchronized (this) {
        reconnectAttempt++;
        lastError = "Stream 重连失败: " + sanitize(e.getMessage());
        LOG.warn(
            "钉钉渠道 {} 重连失败（第 {} 次）: {}",
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
      streamRef.set(client);
      reconnectAttempt = 0;
      state = ChannelStatus.State.CONNECTED;
      lastError = null;
      LOG.info("钉钉渠道 {} Stream 已恢复", sanitize(config.name()));
    }
  }

  private ScheduledExecutorService reconnectScheduler() {
    ScheduledExecutorService scheduler = reconnectScheduler;
    if (scheduler == null) {
      ScheduledThreadPoolExecutor pool =
          new ScheduledThreadPoolExecutor(
              1,
              r -> {
                Thread t = new Thread(r, "dingtalk-reconnect-" + sanitize(config.name()));
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

  /** 归一化 → 去重占用 →（有图时即时「处理中」）→ downloadCode 落盘 → 编排。去重在下载前，避免平台重推白下图。 */
  private void handleBotMessage(JsonNode body) {
    try {
      String conversationId = body.path("conversationId").asText(null);
      String sessionWebhook = body.path("sessionWebhook").asText(null);
      String atUserId = body.path("senderStaffId").asText(null);
      if (atUserId == null || atUserId.isBlank()) {
        atUserId = body.path("senderId").asText(null);
      }
      sender.rememberSession(conversationId, sessionWebhook, atUserId);
      Optional<InboundMessage> msg = normalizer.normalize(body);
      msg.ifPresent(this::dispatchClaimed);
    } catch (RuntimeException e) {
      LOG.error("钉钉渠道 {} 事件处理异常: {}", sanitize(config.name()), sanitize(e.getMessage()));
    }
  }

  private void dispatchClaimed(InboundMessage m) {
    if (!inboundMessageService.tryClaim(m.channelName(), m.messageId())) {
      LOG.info("渠道 {} 重复事件已忽略: {}", sanitize(m.channelName()), sanitize(m.messageId()));
      return;
    }
    String replyTo = m.chatKind() == ChatKind.GROUP ? m.messageId() : null;
    CountDownLatch slowWork = null;
    if (DingTalkInboundImageResolver.hasDownloadableMedia(m)) {
      slowWork = inboundMessageService.beginSlowWork(this, m.chatId(), replyTo);
    }
    try {
      DingTalkInboundImageResolver resolver = imageResolver;
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

  private Path createMediaRoot() {
    return io.oryxos.core.channel.InboundMediaRoots.forChannel(config.name(), MEDIA_DIR_PREFIX);
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
