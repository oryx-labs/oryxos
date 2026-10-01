package io.oryxos.channel.slack;

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
 * Slack 机器人入站适配器：一实例 = 一个 App 的一条 Socket Mode 长连接（对称飞书/企微/钉钉）。
 *
 * <p>凭证映射：{@code app_id} = Bot Token（{@code xoxb-}，Web API），{@code app_secret} = App-Level
 * Token（{@code xapp-}，Socket Mode）。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR",
    justification = "socket/normalizer/sender 在 start() 内初始化；sendReply 有显式空判。")
public class SlackChannelAdapter implements InboundChannelAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(SlackChannelAdapter.class);

  public static final String TYPE = "slack";

  private static final Duration START_TIMEOUT = Duration.ofSeconds(20);
  private static final long RECONNECT_BASE_MS = 2_000L;
  private static final long RECONNECT_MAX_MS = 60_000L;
  private static final int RECONNECT_MAX_SHIFT = 5;
  private static final String MEDIA_DIR_PREFIX = "oryxos-slack-media-";

  private final ChannelConfig config;
  private final ProfileRegistry profileRegistry;
  private final InboundMessageService inboundMessageService;
  private final OutboundGuard guard;

  private final AtomicReference<SlackSocketClient> socketRef = new AtomicReference<>();
  private volatile SlackMessageSender sender;
  private volatile SlackEventNormalizer normalizer;
  private volatile SlackInboundMediaResolver mediaResolver;
  private volatile SlackDisconnectKind lastDisconnectKind = SlackDisconnectKind.ABRUPT;
  private volatile ChannelStatus.State state = ChannelStatus.State.DISCONNECTED;
  private volatile String lastError;
  private volatile boolean running;
  private volatile ScheduledExecutorService reconnectScheduler;
  private volatile ScheduledFuture<?> reconnectFuture;
  private int reconnectAttempt;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "协作者均为 Runtime 装配的单例，共享引用正是意图")
  public SlackChannelAdapter(
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
    guard.check(SlackSocketClient.API_BASE_URL);
    guard.check(SlackMessageSender.API_BASE_URL);
    running = true;
    reconnectAttempt = 0;
    cancelReconnectLocked();
    try {
      connectLocked();
      state = ChannelStatus.State.CONNECTED;
      lastError = null;
      LOG.info(
          "Slack 渠道 {} Socket Mode 已建立（Agent: {}）",
          sanitize(config.name()),
          sanitize(config.agent()));
    } catch (Exception e) {
      running = false;
      shutdownReconnectSchedulerLocked();
      state = ChannelStatus.State.ERROR;
      lastError = "Socket Mode 连接失败: " + sanitize(e.getMessage());
      throw new IllegalStateException("渠道 " + config.name() + " " + lastError, e);
    }
  }

  @Override
  public synchronized void stop() {
    running = false;
    cancelReconnectLocked();
    shutdownReconnectSchedulerLocked();
    SlackSocketClient socket = socketRef.getAndSet(null);
    if (socket != null) {
      socket.closeQuietly();
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
    SlackSocketClient socket = socketRef.get();
    if (socket == null || !socket.isConnected()) {
      return ChannelStatus.ok(
          config.name(), TYPE, config.agent(), ChannelStatus.State.DISCONNECTED);
    }
    return ChannelStatus.ok(config.name(), TYPE, config.agent(), ChannelStatus.State.CONNECTED);
  }

  @Override
  public void sendReply(String chatId, String text, String replyToMessageId) {
    SlackMessageSender active = sender;
    if (active == null) {
      throw new IllegalStateException("渠道 " + config.name() + " 未启动，无法发送回复");
    }
    active.send(chatId, text, replyToMessageId);
  }

  @Override
  public Optional<io.oryxos.core.channel.InboundProgressStream> openProgressStream(
      String chatId, String replyToMessageId) {
    SlackMessageSender active = sender;
    if (active == null) {
      return Optional.empty();
    }
    return Optional.of(new SlackProgressStream(active, chatId, replyToMessageId));
  }

  static long reconnectDelayMs(int attempt) {
    return io.oryxos.core.channel.ReconnectBackoff.delayMs(
        attempt, RECONNECT_BASE_MS, RECONNECT_MAX_MS, RECONNECT_MAX_SHIFT);
  }

  private void connectLocked() throws Exception {
    socketRef.set(connect());
  }

  private SlackSocketClient connect() throws Exception {
    ensureOutboundStack();
    // app_id = bot token；app_secret = app-level token
    SlackSocketClient client =
        new SlackSocketClient(
            config.appSecret(), guard, this::handleEvent, this::handleDisconnected);
    client.connect(START_TIMEOUT);
    return client;
  }

  private void ensureOutboundStack() {
    if (normalizer == null) {
      normalizer = new SlackEventNormalizer(config.name());
    }
    if (sender == null) {
      sender = new SlackMessageSender(guard, config.appId(), SlackMessageSender.DEFAULT_CHUNK_SIZE);
    }
    if (mediaResolver == null) {
      mediaResolver =
          new SlackInboundMediaResolver(
              config.appId(),
              io.oryxos.core.channel.InboundMediaRoots.forChannel(config.name(), MEDIA_DIR_PREFIX),
              config.name());
    }
  }

  private void handleDisconnected(SlackDisconnectKind kind) {
    boolean shouldReconnect;
    synchronized (this) {
      if (!running || state == ChannelStatus.State.ERROR) {
        return;
      }
      lastDisconnectKind = kind == null ? SlackDisconnectKind.ABRUPT : kind;
      if (lastDisconnectKind == SlackDisconnectKind.GRACEFUL) {
        reconnectAttempt = 0;
      }
      socketRef.set(null);
      state = ChannelStatus.State.DISCONNECTED;
      shouldReconnect = true;
    }
    if (shouldReconnect) {
      if (lastDisconnectKind == SlackDisconnectKind.GRACEFUL) {
        LOG.info("Slack 渠道 {} Socket Mode 服务端轮换断开，立即重连", sanitize(config.name()));
      } else {
        LOG.warn("Slack 渠道 {} Socket Mode 断开，将自动重连", sanitize(config.name()));
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
          lastDisconnectKind == SlackDisconnectKind.GRACEFUL
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
    SlackSocketClient client;
    try {
      client = connect();
    } catch (Exception e) {
      synchronized (this) {
        reconnectAttempt++;
        lastError = "Socket Mode 重连失败: " + sanitize(e.getMessage());
        LOG.warn(
            "Slack 渠道 {} 重连失败（第 {} 次）: {}",
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
      socketRef.set(client);
      reconnectAttempt = 0;
      state = ChannelStatus.State.CONNECTED;
      lastError = null;
      LOG.info("Slack 渠道 {} Socket Mode 已恢复", sanitize(config.name()));
    }
  }

  private ScheduledExecutorService reconnectScheduler() {
    ScheduledExecutorService scheduler = reconnectScheduler;
    if (scheduler == null) {
      ScheduledThreadPoolExecutor pool =
          new ScheduledThreadPoolExecutor(
              1,
              r -> {
                Thread t = new Thread(r, "slack-reconnect-" + sanitize(config.name()));
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

  private void handleEvent(JsonNode event) {
    try {
      Optional<InboundMessage> msg = normalizer.normalize(event);
      msg.ifPresent(this::dispatchClaimed);
    } catch (RuntimeException e) {
      LOG.error("Slack 渠道 {} 事件处理异常: {}", sanitize(config.name()), sanitize(e.getMessage()));
    }
  }

  private void dispatchClaimed(InboundMessage m) {
    if (!inboundMessageService.tryClaim(m.channelName(), m.messageId())) {
      LOG.info("渠道 {} 重复事件已忽略: {}", sanitize(m.channelName()), sanitize(m.messageId()));
      return;
    }
    String replyTo = m.chatKind() == io.oryxos.core.channel.ChatKind.GROUP ? m.messageId() : null;
    java.util.concurrent.CountDownLatch slowWork = null;
    if (SlackInboundMediaResolver.hasDownloadableMedia(m)) {
      slowWork = inboundMessageService.beginSlowWork(this, m.chatId(), replyTo);
    }
    try {
      SlackInboundMediaResolver resolver = mediaResolver;
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
