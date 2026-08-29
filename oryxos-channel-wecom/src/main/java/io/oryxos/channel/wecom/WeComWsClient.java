package io.oryxos.channel.wecom;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 企微智能机器人 WebSocket 长连接：订阅鉴权、心跳、收帧、发帧。自动重连由外层适配器控制。 */
final class WeComWsClient implements WebSocket.Listener {

  private static final Logger LOG = LoggerFactory.getLogger(WeComWsClient.class);

  static final String DEFAULT_WS_URL = "wss://openws.work.weixin.qq.com";
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
  private static final long HEARTBEAT_INTERVAL_SEC = 30;
  private static final String FIELD_ERRCODE = "errcode";
  private static final String FIELD_ERRMSG = "errmsg";
  private static final String CMD_AIBOT_SUBSCRIBE = "aibot_subscribe";
  private static final String CMD_PING = "ping";
  private static final String CMD_PONG = "pong";
  private static final int ERRCODE_OK = 0;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String botId;
  private final String secret;
  private final String wsUrl;
  private final Consumer<JsonNode> onFrame;
  private final Runnable onDisconnected;

  private final AtomicReference<WebSocket> socket = new AtomicReference<>();
  private final AtomicBoolean subscribed = new AtomicBoolean(false);
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final StringBuilder textBuf = new StringBuilder();
  private final CountDownLatch subscribeLatch = new CountDownLatch(1);
  private volatile String subscribeError;
  private ScheduledExecutorService heartbeat;
  private ScheduledFuture<?> heartbeatTask;

  WeComWsClient(
      String botId,
      String secret,
      String wsUrl,
      Consumer<JsonNode> onFrame,
      Runnable onDisconnected) {
    this.botId = Objects.requireNonNull(botId);
    this.secret = Objects.requireNonNull(secret);
    this.wsUrl = wsUrl == null || wsUrl.isBlank() ? DEFAULT_WS_URL : wsUrl;
    this.onFrame = Objects.requireNonNull(onFrame);
    this.onDisconnected = onDisconnected == null ? () -> {} : onDisconnected;
  }

  void connectAndSubscribe(Duration timeout) throws Exception {
    closed.set(false);
    HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    CompletableFuture<WebSocket> future =
        client
            .newWebSocketBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .buildAsync(URI.create(wsUrl), this);
    WebSocket ws = future.get(timeout.toSeconds(), TimeUnit.SECONDS);
    socket.set(ws);
    if (!subscribeLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
      closeQuietly();
      throw new IllegalStateException("企微长连接订阅超时（未收到 " + CMD_AIBOT_SUBSCRIBE + " 回执）");
    }
    if (subscribeError != null) {
      closeQuietly();
      throw new IllegalStateException("企微长连接订阅失败: " + subscribeError);
    }
    if (!subscribed.get()) {
      closeQuietly();
      throw new IllegalStateException("企微长连接订阅失败（未知原因）");
    }
  }

  boolean isSubscribed() {
    return subscribed.get() && !closed.get();
  }

  void sendJson(ObjectNode frame) {
    WebSocket ws = socket.get();
    if (ws == null || closed.get()) {
      throw new IllegalStateException("企微长连接未建立，无法发送");
    }
    try {
      String payload = MAPPER.writeValueAsString(frame);
      ws.sendText(payload, true).join();
    } catch (Exception e) {
      throw new IllegalStateException("企微长连接发送失败: " + e.getMessage(), e);
    }
  }

  void closeQuietly() {
    closed.set(true);
    subscribed.set(false);
    stopHeartbeat();
    WebSocket ws = socket.getAndSet(null);
    if (ws != null) {
      try {
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
      } catch (RuntimeException ignored) {
        // ignore
      }
    }
  }

  @Override
  public void onOpen(WebSocket webSocket) {
    webSocket.request(1);
    ObjectNode body = MAPPER.createObjectNode();
    body.put("bot_id", botId);
    body.put("secret", secret);
    ObjectNode headers = MAPPER.createObjectNode();
    headers.put("req_id", UUID.randomUUID().toString());
    ObjectNode frame = MAPPER.createObjectNode();
    frame.put("cmd", CMD_AIBOT_SUBSCRIBE);
    frame.set("headers", headers);
    frame.set("body", body);
    try {
      webSocket.sendText(MAPPER.writeValueAsString(frame), true);
    } catch (Exception e) {
      subscribeError = e.getMessage();
      subscribeLatch.countDown();
    }
  }

  @Override
  public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
    textBuf.append(data);
    if (last) {
      String raw = textBuf.toString();
      textBuf.setLength(0);
      handleText(raw);
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
    closed.set(true);
    subscribed.set(false);
    stopHeartbeat();
    subscribeLatch.countDown();
    onDisconnected.run();
    return null;
  }

  @Override
  public void onError(WebSocket webSocket, Throwable error) {
    LOG.warn("企微长连接错误: {}", sanitize(error == null ? null : error.getMessage()));
    if (!subscribed.get()) {
      subscribeError = error == null ? "unknown" : error.getMessage();
      subscribeLatch.countDown();
    }
    onDisconnected.run();
  }

  private void handleText(String raw) {
    JsonNode root;
    try {
      root = MAPPER.readTree(raw);
    } catch (JacksonException e) {
      LOG.warn("企微帧 JSON 解析失败，已忽略");
      return;
    }
    if (!subscribed.get() && root.has(FIELD_ERRCODE)) {
      handleSubscribeAck(root);
      return;
    }
    String cmd = root.path("cmd").asText("");
    if (isIgnorableControlFrame(cmd, root)) {
      return;
    }
    try {
      onFrame.accept(root);
    } catch (RuntimeException e) {
      LOG.error("企微帧处理异常: {}", sanitize(e.getMessage()));
    }
  }

  private void handleSubscribeAck(JsonNode root) {
    int code = root.path(FIELD_ERRCODE).asInt(-1);
    if (code == ERRCODE_OK) {
      subscribed.set(true);
      startHeartbeat();
      subscribeLatch.countDown();
      return;
    }
    subscribeError = root.path(FIELD_ERRMSG).asText(FIELD_ERRCODE + "=" + code);
    subscribeLatch.countDown();
  }

  private static boolean isIgnorableControlFrame(String cmd, JsonNode root) {
    if (CMD_PONG.equals(cmd)) {
      return true;
    }
    return cmd.isBlank() && root.has(FIELD_ERRCODE);
  }

  private void startHeartbeat() {
    stopHeartbeat();
    ScheduledThreadPoolExecutor pool =
        new ScheduledThreadPoolExecutor(
            1,
            r -> {
              Thread t = new Thread(r, "wecom-ws-heartbeat");
              t.setDaemon(true);
              return t;
            });
    pool.setRemoveOnCancelPolicy(true);
    heartbeat = pool;
    heartbeatTask =
        heartbeat.scheduleAtFixedRate(
            () -> {
              try {
                ObjectNode headers = MAPPER.createObjectNode();
                headers.put("req_id", UUID.randomUUID().toString());
                ObjectNode frame = MAPPER.createObjectNode();
                frame.put("cmd", CMD_PING);
                frame.set("headers", headers);
                sendJson(frame);
              } catch (RuntimeException e) {
                LOG.warn("企微心跳失败: {}", sanitize(e.getMessage()));
              }
            },
            HEARTBEAT_INTERVAL_SEC,
            HEARTBEAT_INTERVAL_SEC,
            TimeUnit.SECONDS);
  }

  private void stopHeartbeat() {
    if (heartbeatTask != null) {
      heartbeatTask.cancel(false);
      heartbeatTask = null;
    }
    if (heartbeat != null) {
      heartbeat.shutdownNow();
      heartbeat = null;
    }
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
