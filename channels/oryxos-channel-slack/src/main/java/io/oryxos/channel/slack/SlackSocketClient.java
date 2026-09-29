package io.oryxos.channel.slack;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.OutboundGuard;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Slack Socket Mode WebSocket：{@code apps.connections.open} 取 URL、收 envelope、ACK、事件回调。
 *
 * <p>凭证：App-Level Token（{@code xapp-}）开连接；事件载荷里的用户消息再交给上层。
 */
final class SlackSocketClient implements WebSocket.Listener {

  private static final Logger LOG = LoggerFactory.getLogger(SlackSocketClient.class);

  static final String API_BASE_URL = "https://slack.com";
  static final String CONNECTIONS_OPEN_PATH = "/api/apps.connections.open";
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
  private static final long WS_CLOSE_TIMEOUT_MS = 2_000L;
  private static final int HTTP_STATUS_OK_MIN = 200;
  private static final int HTTP_STATUS_OK_MAX_EXCLUSIVE = 300;
  private static final String TYPE_EVENTS_API = "events_api";
  private static final String TYPE_HELLO = "hello";
  private static final String TYPE_DISCONNECT = "disconnect";
  private static final String FIELD_OK = "ok";
  private static final String FIELD_ERROR = "error";
  private static final String FIELD_URL = "url";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String appToken;
  private final OutboundGuard guard;
  private final Consumer<JsonNode> onEvent;
  private final Consumer<SlackDisconnectKind> onDisconnected;

  private final AtomicReference<WebSocket> socket = new AtomicReference<>();
  private final AtomicBoolean connected = new AtomicBoolean(false);
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final StringBuilder textBuf = new StringBuilder();
  private final CountDownLatch openLatch = new CountDownLatch(1);
  private volatile String openError;
  private final AtomicBoolean disconnectNotified = new AtomicBoolean(false);

  SlackSocketClient(
      String appToken,
      OutboundGuard guard,
      Consumer<JsonNode> onEvent,
      Consumer<SlackDisconnectKind> onDisconnected) {
    this.appToken = Objects.requireNonNull(appToken);
    this.guard = Objects.requireNonNull(guard);
    this.onEvent = Objects.requireNonNull(onEvent);
    this.onDisconnected = onDisconnected == null ? kind -> {} : onDisconnected;
  }

  void connect(Duration timeout) throws Exception {
    closed.set(false);
    disconnectNotified.set(false);
    guard.check(API_BASE_URL);
    URI wsUri = openWebSocketUri();
    // 沙箱只认 http/https；WSS 主机用 https 伪 URL 过白名单（对齐钉钉：开连接 HTTPS 过闸，WS 端点来自平台响应）
    String host = wsUri.getHost();
    if (host == null || host.isBlank()) {
      throw new IllegalStateException("Slack Socket Mode URL 缺少主机名");
    }
    guard.check("https://" + host);
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
    if (!openLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
      closeQuietly();
      throw new IllegalStateException("Slack Socket Mode 连接超时（WebSocket 未就绪）");
    }
    if (openError != null) {
      closeQuietly();
      throw new IllegalStateException("Slack Socket Mode 连接失败: " + openError);
    }
    if (!connected.get()) {
      closeQuietly();
      throw new IllegalStateException("Slack Socket Mode 连接失败（未知原因）");
    }
  }

  boolean isConnected() {
    return connected.get() && !closed.get();
  }

  /** 限时关闭：{@code sendClose().join()} 偶发不返回时放弃等待（对齐飞书 #417），守护线程后台继续。 */
  void closeQuietly() {
    closed.set(true);
    connected.set(false);
    openLatch.countDown();
    WebSocket ws = socket.getAndSet(null);
    if (ws == null) {
      return;
    }
    Thread closer =
        Thread.ofPlatform()
            .daemon(true)
            .name("oryxos-slack-ws-close")
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
      LOG.warn("Slack WS close {}ms 未返回，放弃等待（守护线程后台继续）", WS_CLOSE_TIMEOUT_MS);
    }
  }

  @Override
  public void onOpen(WebSocket webSocket) {
    connected.set(true);
    disconnectNotified.set(false);
    openLatch.countDown();
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
    notifyDisconnected(SlackDisconnectKind.ABRUPT, "close " + statusCode + " " + reason);
    return null;
  }

  @Override
  public void onError(WebSocket webSocket, Throwable error) {
    LOG.warn("Slack Socket Mode 连接错误: {}", sanitize(error == null ? null : error.getMessage()));
    if (!connected.get()) {
      openError = error == null ? "unknown" : error.getMessage();
      openLatch.countDown();
    }
    notifyDisconnected(SlackDisconnectKind.ABRUPT, error == null ? null : error.getMessage());
  }

  /** 单测：注入原始 envelope JSON。 */
  void dispatchFrameForTest(String raw, WebSocket webSocket) {
    handleText(webSocket, raw);
  }

  private void handleText(WebSocket webSocket, String raw) {
    JsonNode root;
    try {
      root = MAPPER.readTree(raw);
    } catch (JacksonException e) {
      LOG.warn("Slack Socket Mode 帧 JSON 解析失败，已忽略");
      return;
    }
    String type = root.path("type").asText("");
    String envelopeId = root.path("envelope_id").asText("");
    if (TYPE_HELLO.equals(type)) {
      return;
    }
    if (TYPE_DISCONNECT.equals(type)) {
      LOG.info("Slack Socket Mode 服务端请求断开: {}", sanitize(root.path("reason").asText("")));
      ack(webSocket, envelopeId);
      closeQuietly();
      notifyDisconnected(SlackDisconnectKind.GRACEFUL, root.path("reason").asText(""));
      return;
    }
    if (TYPE_EVENTS_API.equals(type)) {
      ack(webSocket, envelopeId);
      JsonNode payload = root.path("payload");
      JsonNode event = payload.path("event");
      if (event.isMissingNode() || !event.isObject()) {
        return;
      }
      try {
        onEvent.accept(event);
      } catch (RuntimeException e) {
        LOG.error("Slack 事件处理异常: {}", sanitize(e.getMessage()));
      }
    }
  }

  private void ack(WebSocket webSocket, String envelopeId) {
    if (webSocket == null || envelopeId == null || envelopeId.isBlank()) {
      return;
    }
    try {
      ObjectNode ack = MAPPER.createObjectNode();
      ack.put("envelope_id", envelopeId);
      webSocket.sendText(MAPPER.writeValueAsString(ack), true);
    } catch (Exception e) {
      LOG.warn("Slack Socket Mode ACK 发送失败: {}", sanitize(e.getMessage()));
    }
  }

  private URI openWebSocketUri() throws Exception {
    HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(API_BASE_URL + CONNECTIONS_OPEN_PATH))
            .timeout(CONNECT_TIMEOUT)
            .header("Authorization", "Bearer " + appToken)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < HTTP_STATUS_OK_MIN
        || response.statusCode() >= HTTP_STATUS_OK_MAX_EXCLUSIVE) {
      throw new IllegalStateException(
          "Slack apps.connections.open 失败 HTTP "
              + response.statusCode()
              + ": "
              + sanitize(response.body()));
    }
    JsonNode json = MAPPER.readTree(response.body());
    if (!json.path(FIELD_OK).asBoolean(false)) {
      throw new IllegalStateException(
          "Slack apps.connections.open 业务失败: "
              + sanitize(json.path(FIELD_ERROR).asText("unknown")));
    }
    String url = json.path(FIELD_URL).asText(null);
    if (url == null || url.isBlank()) {
      throw new IllegalStateException("Slack apps.connections.open 响应缺少 url");
    }
    return URI.create(url);
  }

  private void markDisconnected() {
    closed.set(true);
    connected.set(false);
    openLatch.countDown();
  }

  private void notifyDisconnected(SlackDisconnectKind kind, String detail) {
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
