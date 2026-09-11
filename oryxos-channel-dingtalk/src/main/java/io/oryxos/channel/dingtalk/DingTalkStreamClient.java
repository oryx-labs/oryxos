package io.oryxos.channel.dingtalk;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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

/** 钉钉 Stream WebSocket：注册 ticket、收帧、ACK、机器人消息回调。 */
final class DingTalkStreamClient implements WebSocket.Listener {

  private static final Logger LOG = LoggerFactory.getLogger(DingTalkStreamClient.class);

  static final String TOPIC_BOT_MESSAGE = "/v1.0/im/bot/messages/get";
  static final String OPEN_CONNECTION_PATH = "/v1.0/gateway/connections/open";
  static final String API_BASE_URL = "https://api.dingtalk.com";
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
  private static final String TYPE_CALLBACK = "CALLBACK";
  private static final String TYPE_SYSTEM = "SYSTEM";
  private static final String TOPIC_PING = "ping";
  private static final String TOPIC_DISCONNECT = "disconnect";
  private static final String ACK_BOT_DATA = "{\"response\": null}";
  private static final int HTTP_STATUS_OK_MIN = 200;
  private static final int HTTP_STATUS_OK_MAX_EXCLUSIVE = 300;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String clientId;
  private final String clientSecret;
  private final OutboundGuard guard;
  private final Consumer<JsonNode> onBotMessage;
  private final Consumer<DingTalkDisconnectKind> onDisconnected;

  private final AtomicReference<WebSocket> socket = new AtomicReference<>();
  private final AtomicBoolean connected = new AtomicBoolean(false);
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final StringBuilder textBuf = new StringBuilder();
  private final CountDownLatch openLatch = new CountDownLatch(1);
  private volatile String openError;
  private volatile long connectedAtMillis;
  private final AtomicBoolean disconnectNotified = new AtomicBoolean(false);

  DingTalkStreamClient(
      String clientId,
      String clientSecret,
      OutboundGuard guard,
      Consumer<JsonNode> onBotMessage,
      Consumer<DingTalkDisconnectKind> onDisconnected) {
    this.clientId = Objects.requireNonNull(clientId);
    this.clientSecret = Objects.requireNonNull(clientSecret);
    this.guard = Objects.requireNonNull(guard);
    this.onBotMessage = Objects.requireNonNull(onBotMessage);
    this.onDisconnected = onDisconnected == null ? kind -> {} : onDisconnected;
  }

  void connect(Duration timeout) throws Exception {
    closed.set(false);
    disconnectNotified.set(false);
    guard.check(API_BASE_URL);
    URI wsUri = openWebSocketUri();
    HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    CompletableFuture<WebSocket> future =
        client.newWebSocketBuilder().connectTimeout(CONNECT_TIMEOUT).buildAsync(wsUri, this);
    WebSocket ws = future.get(timeout.toSeconds(), TimeUnit.SECONDS);
    socket.set(ws);
    if (!openLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
      closeQuietly();
      throw new IllegalStateException("钉钉 Stream 连接超时（WebSocket 未就绪）");
    }
    if (openError != null) {
      closeQuietly();
      throw new IllegalStateException("钉钉 Stream 连接失败: " + openError);
    }
    if (!connected.get()) {
      closeQuietly();
      throw new IllegalStateException("钉钉 Stream 连接失败（未知原因）");
    }
  }

  boolean isConnected() {
    return connected.get() && !closed.get();
  }

  void closeQuietly() {
    closed.set(true);
    connected.set(false);
    openLatch.countDown();
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
    connected.set(true);
    connectedAtMillis = System.currentTimeMillis();
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
    notifyDisconnected(DingTalkDisconnectKind.ABRUPT, "close " + statusCode + " " + reason);
    return null;
  }

  @Override
  public void onError(WebSocket webSocket, Throwable error) {
    LOG.warn("钉钉 Stream 连接错误: {}", sanitize(error == null ? null : error.getMessage()));
    if (!connected.get()) {
      openError = error == null ? "unknown" : error.getMessage();
      openLatch.countDown();
    }
    notifyDisconnected(DingTalkDisconnectKind.ABRUPT, error == null ? null : error.getMessage());
  }

  void dispatchFrameForTest(String raw, WebSocket webSocket) {
    handleText(webSocket, raw);
  }

  private void handleText(WebSocket webSocket, String raw) {
    JsonNode root;
    try {
      root = MAPPER.readTree(raw);
    } catch (JacksonException e) {
      LOG.warn("钉钉 Stream 帧 JSON 解析失败，已忽略");
      return;
    }
    String type = root.path("type").asText("");
    JsonNode headers = root.path("headers");
    String messageId = headers.path("messageId").asText("");
    String topic = headers.path("topic").asText("");
    if (TYPE_SYSTEM.equals(type)) {
      handleSystem(webSocket, topic, messageId, root.path("data").asText(""));
      return;
    }
    if (TYPE_CALLBACK.equals(type) && TOPIC_BOT_MESSAGE.equals(topic)) {
      handleBotMessage(webSocket, messageId, root.path("data").asText(""));
    }
  }

  private void handleSystem(WebSocket webSocket, String topic, String messageId, String dataRaw) {
    if (TOPIC_PING.equals(topic)) {
      try {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("opaque", extractOpaque(dataRaw));
        sendAck(webSocket, messageId, MAPPER.writeValueAsString(data));
      } catch (JacksonException e) {
        sendAck(webSocket, messageId, "{\"opaque\":\"\"}");
      }
      return;
    }
    if (TOPIC_DISCONNECT.equals(topic)) {
      long uptimeMs = connectedAtMillis > 0 ? System.currentTimeMillis() - connectedAtMillis : -1L;
      LOG.info("钉钉 Stream 服务端请求断开（uptime={}ms）: {}", uptimeMs, sanitize(dataRaw));
      closeQuietly();
      notifyDisconnected(DingTalkDisconnectKind.GRACEFUL, dataRaw);
      return;
    }
  }

  private void handleBotMessage(WebSocket webSocket, String messageId, String dataRaw) {
    sendAck(webSocket, messageId, ACK_BOT_DATA);
    if (dataRaw == null || dataRaw.isBlank()) {
      return;
    }
    try {
      JsonNode data = MAPPER.readTree(dataRaw);
      onBotMessage.accept(data);
    } catch (JacksonException e) {
      LOG.warn("钉钉机器人消息 data 解析失败，已忽略");
    } catch (RuntimeException e) {
      LOG.error("钉钉机器人消息处理异常: {}", sanitize(e.getMessage()));
    }
  }

  private void sendAck(WebSocket webSocket, String messageId, String dataJson) {
    if (webSocket == null || messageId == null || messageId.isBlank()) {
      return;
    }
    try {
      ObjectNode headers = MAPPER.createObjectNode();
      headers.put("messageId", messageId);
      headers.put("contentType", "application/json");
      ObjectNode ack = MAPPER.createObjectNode();
      ack.put("code", 200);
      ack.put("message", "OK");
      ack.set("headers", headers);
      ack.put("data", dataJson);
      webSocket.sendText(MAPPER.writeValueAsString(ack), true);
    } catch (Exception e) {
      LOG.warn("钉钉 Stream ACK 发送失败: {}", sanitize(e.getMessage()));
    }
  }

  private URI openWebSocketUri() throws Exception {
    ObjectNode subscription = MAPPER.createObjectNode();
    subscription.put("topic", TOPIC_BOT_MESSAGE);
    subscription.put("type", "CALLBACK");
    ArrayNode subscriptions = MAPPER.createArrayNode();
    subscriptions.add(subscription);
    ObjectNode body = MAPPER.createObjectNode();
    body.put("clientId", clientId);
    body.put("clientSecret", clientSecret);
    body.set("subscriptions", subscriptions);
    body.put("ua", "oryxos-channel-dingtalk/0.1.5-RELEASE");
    HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(API_BASE_URL + OPEN_CONNECTION_PATH))
            .timeout(CONNECT_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
            .build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < HTTP_STATUS_OK_MIN
        || response.statusCode() >= HTTP_STATUS_OK_MAX_EXCLUSIVE) {
      throw new IllegalStateException(
          "钉钉 Stream 注册失败 HTTP " + response.statusCode() + ": " + response.body());
    }
    JsonNode json = MAPPER.readTree(response.body());
    String endpoint = json.path("endpoint").asText(null);
    String ticket = json.path("ticket").asText(null);
    if (endpoint == null || endpoint.isBlank() || ticket == null || ticket.isBlank()) {
      throw new IllegalStateException("钉钉 Stream 注册响应缺少 endpoint/ticket");
    }
    String separator = endpoint.contains("?") ? "&" : "?";
    return URI.create(endpoint + separator + "ticket=" + ticket);
  }

  private void markDisconnected() {
    closed.set(true);
    connected.set(false);
    openLatch.countDown();
  }

  private void notifyDisconnected(DingTalkDisconnectKind kind, String detail) {
    if (!disconnectNotified.compareAndSet(false, true)) {
      return;
    }
    markDisconnected();
    onDisconnected.accept(kind);
  }

  private static String extractOpaque(String dataRaw) {
    if (dataRaw == null || dataRaw.isBlank()) {
      return "";
    }
    try {
      return MAPPER.readTree(dataRaw).path("opaque").asText("");
    } catch (JacksonException e) {
      return "";
    }
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
