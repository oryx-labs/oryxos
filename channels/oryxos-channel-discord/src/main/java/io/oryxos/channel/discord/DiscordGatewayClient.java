package io.oryxos.channel.discord;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.OutboundGuard;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discord Gateway WebSocket（v10）：Hello → Identify → Heartbeat；Dispatch {@code MESSAGE_CREATE}。
 *
 * <p>凭证：Bot Token。出站主机过 {@link OutboundGuard}。
 */
final class DiscordGatewayClient implements WebSocket.Listener {

  private static final Logger LOG = LoggerFactory.getLogger(DiscordGatewayClient.class);

  static final String API_BASE_URL = "https://discord.com";
  static final String GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json";
  static final String GATEWAY_ORIGIN_FOR_GUARD = "https://gateway.discord.gg";

  /** GUILDS | GUILD_MESSAGES | DIRECT_MESSAGES | MESSAGE_CONTENT */
  static final int INTENTS = (1 << 0) | (1 << 9) | (1 << 12) | (1 << 15);

  private static final int OP_DISPATCH = 0;
  private static final int OP_HEARTBEAT = 1;
  private static final int OP_IDENTIFY = 2;
  private static final int OP_RECONNECT = 7;
  private static final int OP_INVALID_SESSION = 9;
  private static final int OP_HELLO = 10;
  private static final int OP_HEARTBEAT_ACK = 11;

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
  private static final long WS_CLOSE_TIMEOUT_MS = 2_000L;
  private static final long READY_TIMEOUT_MS = 20_000L;
  private static final String FIELD_OP = "op";
  private static final String FIELD_D = "d";
  private static final String FIELD_S = "s";
  private static final String FIELD_T = "t";
  private static final String EVENT_READY = "READY";
  private static final long DEFAULT_HEARTBEAT_MS = 41_250L;
  private static final long MIN_HEARTBEAT_MS = 1_000L;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String botToken;
  private final OutboundGuard guard;
  private final BiConsumer<String, JsonNode> onDispatch;
  private final Consumer<DiscordDisconnectKind> onDisconnected;

  private final AtomicReference<WebSocket> socket = new AtomicReference<>();
  private final AtomicBoolean connected = new AtomicBoolean(false);
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final AtomicBoolean identified = new AtomicBoolean(false);
  private final StringBuilder textBuf = new StringBuilder();
  private final CountDownLatch readyLatch = new CountDownLatch(1);
  private volatile String openError;
  private final AtomicBoolean disconnectNotified = new AtomicBoolean(false);
  private final AtomicLong lastSeq = new AtomicLong(-1);
  private volatile ScheduledExecutorService heartbeatScheduler;
  private volatile ScheduledFuture<?> heartbeatFuture;

  DiscordGatewayClient(
      String botToken,
      OutboundGuard guard,
      BiConsumer<String, JsonNode> onDispatch,
      Consumer<DiscordDisconnectKind> onDisconnected) {
    this.botToken = Objects.requireNonNull(botToken);
    this.guard = Objects.requireNonNull(guard);
    this.onDispatch = Objects.requireNonNull(onDispatch);
    this.onDisconnected = onDisconnected == null ? kind -> {} : onDisconnected;
  }

  void connect(Duration timeout) throws Exception {
    closed.set(false);
    disconnectNotified.set(false);
    identified.set(false);
    lastSeq.set(-1);
    openError = null;
    guard.check(API_BASE_URL);
    guard.check(GATEWAY_ORIGIN_FOR_GUARD);
    URI wsUri = URI.create(GATEWAY_URL);
    HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    CompletableFuture<WebSocket> future =
        client.newWebSocketBuilder().connectTimeout(CONNECT_TIMEOUT).buildAsync(wsUri, this);
    WebSocket ws;
    try {
      ws = future.get(timeout.toSeconds(), TimeUnit.SECONDS);
    } catch (Exception e) {
      future.cancel(true);
      closeQuietly();
      throw e;
    }
    socket.set(ws);
    long waitMs = Math.max(timeout.toMillis(), READY_TIMEOUT_MS);
    if (!readyLatch.await(waitMs, TimeUnit.MILLISECONDS)) {
      closeQuietly();
      throw new IllegalStateException("Discord Gateway 连接超时（未收到 READY）");
    }
    if (openError != null) {
      closeQuietly();
      throw new IllegalStateException("Discord Gateway 连接失败: " + openError);
    }
    if (!connected.get() || !identified.get()) {
      closeQuietly();
      throw new IllegalStateException("Discord Gateway 连接失败（未知原因）");
    }
  }

  boolean isConnected() {
    return connected.get() && identified.get() && !closed.get();
  }

  void closeQuietly() {
    closed.set(true);
    connected.set(false);
    identified.set(false);
    stopHeartbeat();
    readyLatch.countDown();
    WebSocket ws = socket.getAndSet(null);
    if (ws == null) {
      return;
    }
    Thread closer =
        Thread.ofPlatform()
            .daemon(true)
            .name("oryxos-discord-ws-close")
            .unstarted(
                () -> {
                  try {
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
                  } catch (RuntimeException ignored) {
                    // ignore
                  }
                });
    closer.start();
    try {
      closer.join(WS_CLOSE_TIMEOUT_MS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (closer.isAlive()) {
      LOG.warn("Discord WS close {}ms 未返回，放弃等待（守护线程后台继续）", WS_CLOSE_TIMEOUT_MS);
    }
  }

  @Override
  public void onOpen(WebSocket webSocket) {
    connected.set(true);
    disconnectNotified.set(false);
    webSocket.request(1);
  }

  @Override
  public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
    textBuf.append(data);
    if (last) {
      String raw = textBuf.toString();
      textBuf.setLength(0);
      handleText(webSocket, raw);
    }
    webSocket.request(1);
    return null;
  }

  @Override
  public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
    webSocket.request(1);
    return null;
  }

  @Override
  public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
    notifyDisconnected(DiscordDisconnectKind.ABRUPT, "close " + statusCode + " " + reason);
    return null;
  }

  @Override
  public void onError(WebSocket webSocket, Throwable error) {
    LOG.warn("Discord Gateway 连接错误: {}", sanitize(error == null ? null : error.getMessage()));
    if (!identified.get()) {
      openError = error == null ? "unknown" : error.getMessage();
      readyLatch.countDown();
    }
    notifyDisconnected(DiscordDisconnectKind.ABRUPT, error == null ? null : error.getMessage());
  }

  /** 单测：注入原始 Gateway JSON。 */
  void dispatchFrameForTest(String raw) {
    handleText(socket.get(), raw);
  }

  private void handleText(WebSocket webSocket, String raw) {
    JsonNode root;
    try {
      root = MAPPER.readTree(raw);
    } catch (JacksonException e) {
      LOG.warn("Discord Gateway 帧 JSON 解析失败，已忽略");
      return;
    }
    int op = root.path(FIELD_OP).asInt(-1);
    JsonNode d = root.get(FIELD_D);
    if (!root.path(FIELD_S).isNull() && root.has(FIELD_S) && root.get(FIELD_S).canConvertToLong()) {
      lastSeq.set(root.get(FIELD_S).asLong());
    }
    if (op == OP_HELLO) {
      onHello(webSocket, d);
    } else if (op == OP_HEARTBEAT_ACK) {
      // ok
    } else if (op == OP_HEARTBEAT) {
      sendHeartbeat(webSocket);
    } else if (op == OP_RECONNECT) {
      LOG.info("Discord Gateway 服务端要求重连 (op=7)");
      closeQuietly();
      notifyDisconnected(DiscordDisconnectKind.GRACEFUL, "reconnect");
    } else if (op == OP_INVALID_SESSION) {
      boolean resumable = d != null && d.asBoolean(false);
      LOG.warn("Discord Gateway Invalid Session resumable={}", resumable);
      closeQuietly();
      notifyDisconnected(DiscordDisconnectKind.ABRUPT, "invalid_session");
    } else if (op == OP_DISPATCH) {
      String t = root.path(FIELD_T).asText("");
      if (EVENT_READY.equals(t)) {
        identified.set(true);
        readyLatch.countDown();
        LOG.info("Discord Gateway READY");
        return;
      }
      if (d != null && d.isObject()) {
        try {
          onDispatch.accept(t, d);
        } catch (RuntimeException e) {
          LOG.error("Discord 事件处理异常: {}", sanitize(e.getMessage()));
        }
      }
    }
  }

  private void onHello(WebSocket webSocket, JsonNode d) {
    long intervalMs =
        d == null
            ? DEFAULT_HEARTBEAT_MS
            : d.path("heartbeat_interval").asLong(DEFAULT_HEARTBEAT_MS);
    startHeartbeat(webSocket, intervalMs);
    sendIdentify(webSocket);
  }

  private void sendIdentify(WebSocket webSocket) {
    try {
      ObjectNode root = MAPPER.createObjectNode();
      root.put(FIELD_OP, OP_IDENTIFY);
      ObjectNode data = root.putObject(FIELD_D);
      data.put("token", botToken);
      data.put("intents", INTENTS);
      ObjectNode props = data.putObject("properties");
      props.put("os", System.getProperty("os.name", "unknown"));
      props.put("browser", "oryxos");
      props.put("device", "oryxos");
      webSocket.sendText(MAPPER.writeValueAsString(root), true);
    } catch (Exception e) {
      openError = e.getMessage();
      readyLatch.countDown();
      LOG.warn("Discord Identify 发送失败: {}", sanitize(e.getMessage()));
    }
  }

  private void startHeartbeat(WebSocket webSocket, long intervalMs) {
    stopHeartbeat();
    ScheduledThreadPoolExecutor pool =
        new ScheduledThreadPoolExecutor(
            1,
            r -> {
              Thread t = new Thread(r, "oryxos-discord-heartbeat");
              t.setDaemon(true);
              return t;
            });
    pool.setRemoveOnCancelPolicy(true);
    heartbeatScheduler = pool;
    long delay = Math.max(MIN_HEARTBEAT_MS, intervalMs);
    heartbeatFuture =
        pool.scheduleAtFixedRate(
            () -> sendHeartbeat(webSocket), delay, delay, TimeUnit.MILLISECONDS);
  }

  private void sendHeartbeat(WebSocket webSocket) {
    if (webSocket == null || closed.get()) {
      return;
    }
    try {
      ObjectNode root = MAPPER.createObjectNode();
      root.put(FIELD_OP, OP_HEARTBEAT);
      long seq = lastSeq.get();
      if (seq >= 0) {
        root.put(FIELD_D, seq);
      } else {
        root.putNull(FIELD_D);
      }
      webSocket.sendText(MAPPER.writeValueAsString(root), true);
    } catch (Exception e) {
      LOG.warn("Discord Heartbeat 发送失败: {}", sanitize(e.getMessage()));
    }
  }

  private void stopHeartbeat() {
    ScheduledFuture<?> future = heartbeatFuture;
    if (future != null) {
      future.cancel(false);
      heartbeatFuture = null;
    }
    ScheduledExecutorService scheduler = heartbeatScheduler;
    if (scheduler != null) {
      scheduler.shutdownNow();
      heartbeatScheduler = null;
    }
  }

  private void markDisconnected() {
    closed.set(true);
    connected.set(false);
    identified.set(false);
    stopHeartbeat();
    readyLatch.countDown();
  }

  private void notifyDisconnected(DiscordDisconnectKind kind, String detail) {
    if (!disconnectNotified.compareAndSet(false, true)) {
      return;
    }
    markDisconnected();
    onDisconnected.accept(kind);
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
