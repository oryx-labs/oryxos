package io.oryxos.channel.discord;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.ChannelStatus;
import io.oryxos.core.channel.InboundChannelAdapter;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageService;
import io.oryxos.core.channel.OutboundGuard;
import io.oryxos.core.profile.ProfileRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discord 机器人入站适配器：一实例 = 一个 Bot 的一条 Gateway 长连接（对称 Slack/飞书）。
 *
 * <p>凭证映射：{@code app_id} = Bot Token，{@code app_secret} = Application ID（提及匹配）。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR",
    justification = "gateway/normalizer/sender 在 start() 内初始化；sendReply 有显式空判。")
public class DiscordChannelAdapter implements InboundChannelAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(DiscordChannelAdapter.class);

  public static final String TYPE = "discord";

  private static final Duration START_TIMEOUT = Duration.ofSeconds(25);
  private static final long RECONNECT_BASE_MS = 2_000L;
  private static final long RECONNECT_MAX_MS = 60_000L;
  private static final int RECONNECT_MAX_SHIFT = 5;
  private static final String MEDIA_DIR_PREFIX = "oryxos-discord-media-";

  private final ChannelConfig config;
  private final ProfileRegistry profileRegistry;
  private final InboundMessageService inboundMessageService;
  private final OutboundGuard guard;

  private final AtomicReference<DiscordGatewayClient> gatewayRef = new AtomicReference<>();
  private volatile DiscordMessageSender sender;
  private volatile DiscordEventNormalizer normalizer;
  private volatile DiscordInboundMediaResolver mediaResolver;
  private volatile DiscordDisconnectKind lastDisconnectKind = DiscordDisconnectKind.ABRUPT;
  private volatile ChannelStatus.State state = ChannelStatus.State.DISCONNECTED;
  private volatile String lastError;
  private volatile boolean running;
  private volatile ScheduledExecutorService reconnectScheduler;
  private volatile ScheduledFuture<?> reconnectFuture;
  private int reconnectAttempt;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "协作者均为 Runtime 装配的单例，共享引用正是意图")
  public DiscordChannelAdapter(
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
    guard.check(DiscordGatewayClient.API_BASE_URL);
    guard.check(DiscordGatewayClient.GATEWAY_ORIGIN_FOR_GUARD);
    guard.check(DiscordMessageSender.API_BASE_URL);
    running = true;
    reconnectAttempt = 0;
    cancelReconnectLocked();
    try {
      connectLocked();
      state = ChannelStatus.State.CONNECTED;
      lastError = null;
      LOG.info(
          "Discord 渠道 {} Gateway 已建立（Agent: {}）",
          sanitize(config.name()),
          sanitize(config.agent()));
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
    DiscordGatewayClient gateway = gatewayRef.getAndSet(null);
    if (gateway != null) {
      gateway.closeQuietly();
    }
    sender = null;
    normalizer = null;
    mediaResolver = null;
    state = ChannelStatus.State.DISCONNECTED;
  }

  @Override
  public ChannelStatus status() {
    if (state == ChannelStatus.State.ERROR) {
      return ChannelStatus.error(config.name(), TYPE, config.agent(), lastError);
    }
    DiscordGatewayClient gateway = gatewayRef.get();
    if (gateway == null || !gateway.isConnected()) {
      return ChannelStatus.ok(
          config.name(), TYPE, config.agent(), ChannelStatus.State.DISCONNECTED);
    }
    return ChannelStatus.ok(config.name(), TYPE, config.agent(), ChannelStatus.State.CONNECTED);
  }

  @Override
  public void sendReply(String chatId, String text, String replyToMessageId) {
    DiscordMessageSender active = sender;
    if (active == null) {
      throw new IllegalStateException("渠道 " + config.name() + " 未启动，无法发送回复");
    }
    active.send(chatId, text, replyToMessageId);
  }

  @Override
  public Optional<io.oryxos.core.channel.InboundProgressStream> openProgressStream(
      String chatId, String replyToMessageId) {
    DiscordMessageSender active = sender;
    if (active == null) {
      return Optional.empty();
    }
    return Optional.of(new DiscordProgressStream(active, chatId, replyToMessageId));
  }

  static long reconnectDelayMs(int attempt) {
    return io.oryxos.core.channel.ReconnectBackoff.delayMs(
        attempt, RECONNECT_BASE_MS, RECONNECT_MAX_MS, RECONNECT_MAX_SHIFT);
  }

  private void connectLocked() throws Exception {
    gatewayRef.set(connect());
  }

  private DiscordGatewayClient connect() throws Exception {
    ensureOutboundStack();
    DiscordGatewayClient client =
        new DiscordGatewayClient(
            config.appId(), guard, this::handleDispatch, this::handleDisconnected);
    client.connect(START_TIMEOUT);
    return client;
  }

  private void ensureOutboundStack() {
    if (normalizer == null) {
      normalizer = new DiscordEventNormalizer(config.name(), config.appSecret());
    }
    if (sender == null) {
      sender =
          new DiscordMessageSender(guard, config.appId(), DiscordMessageSender.DEFAULT_CHUNK_SIZE);
    }
    if (mediaResolver == null) {
      mediaResolver =
          new DiscordInboundMediaResolver(
              config.appId(),
              io.oryxos.core.channel.InboundMediaRoots.forChannel(config.name(), MEDIA_DIR_PREFIX),
              config.name());
    }
  }

  private void handleDisconnected(DiscordDisconnectKind kind) {
    boolean shouldReconnect;
    synchronized (this) {
      if (!running || state == ChannelStatus.State.ERROR) {
        return;
      }
      lastDisconnectKind = kind == null ? DiscordDisconnectKind.ABRUPT : kind;
      if (lastDisconnectKind == DiscordDisconnectKind.GRACEFUL) {
        reconnectAttempt = 0;
      }
      gatewayRef.set(null);
      state = ChannelStatus.State.DISCONNECTED;
      shouldReconnect = true;
    }
    if (shouldReconnect) {
      if (lastDisconnectKind == DiscordDisconnectKind.GRACEFUL) {
        LOG.info("Discord 渠道 {} Gateway 服务端要求重连，立即重连", sanitize(config.name()));
      } else {
        LOG.warn("Discord 渠道 {} Gateway 断开，将自动重连", sanitize(config.name()));
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
          lastDisconnectKind == DiscordDisconnectKind.GRACEFUL
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
    DiscordGatewayClient client;
    try {
      client = connect();
    } catch (Exception e) {
      synchronized (this) {
        reconnectAttempt++;
        lastError = "Gateway 重连失败: " + sanitize(e.getMessage());
        LOG.warn(
            "Discord 渠道 {} 重连失败（第 {} 次）: {}",
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
      LOG.info("Discord 渠道 {} Gateway 已恢复", sanitize(config.name()));
    }
  }

  private ScheduledExecutorService reconnectScheduler() {
    ScheduledExecutorService scheduler = reconnectScheduler;
    if (scheduler == null) {
      ScheduledThreadPoolExecutor pool =
          new ScheduledThreadPoolExecutor(
              1,
              r -> {
                Thread t = new Thread(r, "discord-reconnect-" + sanitize(config.name()));
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
      Optional<InboundMessage> msg = normalizer.normalize(eventName, data);
      msg.ifPresent(this::dispatchClaimed);
    } catch (RuntimeException e) {
      LOG.error("Discord 渠道 {} 事件处理异常: {}", sanitize(config.name()), sanitize(e.getMessage()));
    }
  }

  private void dispatchClaimed(InboundMessage m) {
    if (!inboundMessageService.tryClaim(m.channelName(), m.messageId())) {
      LOG.info("渠道 {} 重复事件已忽略: {}", sanitize(m.channelName()), sanitize(m.messageId()));
      return;
    }
    String replyTo = m.chatKind() == io.oryxos.core.channel.ChatKind.GROUP ? m.messageId() : null;
    java.util.concurrent.CountDownLatch slowWork = null;
    if (DiscordInboundMediaResolver.hasDownloadableMedia(m)) {
      slowWork = inboundMessageService.beginSlowWork(this, m.chatId(), replyTo);
    }
    try {
      DiscordInboundMediaResolver resolver = mediaResolver;
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
}
