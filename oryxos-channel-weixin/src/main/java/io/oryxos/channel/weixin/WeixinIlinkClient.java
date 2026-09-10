package io.oryxos.channel.weixin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.OutboundGuard;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.function.Supplier;

/** iLink HTTP：getupdates / sendmessage。 */
public class WeixinIlinkClient {

  static final String DEFAULT_BASE = "https://ilinkai.weixin.qq.com";
  static final String EP_GET_UPDATES = "ilink/bot/getupdates";
  static final String EP_SEND_MESSAGE = "ilink/bot/sendmessage";
  static final String CHANNEL_VERSION = "2.2.0";
  static final int APP_CLIENT_VERSION = (2 << 16) | (2 << 8) | 0;
  private static final int ITEM_TEXT = 1;
  private static final int MSG_TYPE_BOT = 2;
  private static final int MSG_STATE_FINISH = 2;
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final SecureRandom RANDOM = new SecureRandom();

  private final HttpClient http;
  private final OutboundGuard guard;
  private final String baseUrl;
  private final Supplier<String> token;

  public WeixinIlinkClient(OutboundGuard guard, String baseUrl, Supplier<String> token) {
    this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(), guard, baseUrl, token);
  }

  WeixinIlinkClient(HttpClient http, OutboundGuard guard, String baseUrl, Supplier<String> token) {
    this.http = http;
    this.guard = guard;
    this.baseUrl = trimSlash(baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE : baseUrl);
    this.token = token;
  }

  public JsonNode getUpdates(String syncBuf, Duration timeout) throws Exception {
    ObjectNode body = MAPPER.createObjectNode();
    body.put("get_updates_buf", syncBuf == null ? "" : syncBuf);
    body.set("base_info", baseInfo());
    return post(EP_GET_UPDATES, body, timeout);
  }

  public JsonNode sendText(String toUserId, String text, String contextToken, String clientId)
      throws Exception {
    ObjectNode msg = MAPPER.createObjectNode();
    msg.put("from_user_id", "");
    msg.put("to_user_id", toUserId);
    msg.put("client_id", clientId);
    msg.put("message_type", MSG_TYPE_BOT);
    msg.put("message_state", MSG_STATE_FINISH);
    if (contextToken != null && !contextToken.isBlank()) {
      msg.put("context_token", contextToken);
    }
    ObjectNode item = MAPPER.createObjectNode();
    item.put("type", ITEM_TEXT);
    item.putObject("text_item").put("text", text == null ? "" : text);
    msg.putArray("item_list").add(item);
    ObjectNode body = MAPPER.createObjectNode();
    body.set("msg", msg);
    body.set("base_info", baseInfo());
    return post(EP_SEND_MESSAGE, body, Duration.ofSeconds(20));
  }

  private JsonNode post(String endpoint, ObjectNode body, Duration timeout) throws Exception {
    String tok = token.get();
    if (tok == null || tok.isBlank()) {
      throw new IllegalStateException("微信 iLink token 为空");
    }
    String json = MAPPER.writeValueAsString(body);
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    String url = baseUrl + "/" + endpoint;
    guard.check(url);
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("AuthorizationType", "ilink_bot_token")
            .header("Authorization", "Bearer " + tok)
            .header("iLink-App-Id", "bot")
            .header("iLink-App-ClientVersion", Integer.toString(APP_CLIENT_VERSION))
            .header("X-WECHAT-UIN", randomWechatUin())
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
            .build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IllegalStateException(
          "iLink HTTP " + response.statusCode() + ": " + sanitize(response.body()));
    }
    return MAPPER.readTree(response.body() == null ? "{}" : response.body());
  }

  private static ObjectNode baseInfo() {
    ObjectNode info = MAPPER.createObjectNode();
    info.put("channel_version", CHANNEL_VERSION);
    return info;
  }

  private static String randomWechatUin() {
    int value = RANDOM.nextInt();
    String asUnsigned = Integer.toUnsignedString(value);
    return Base64.getEncoder().encodeToString(asUnsigned.getBytes(StandardCharsets.UTF_8));
  }

  private static String trimSlash(String base) {
    String s = base.strip();
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }

  private static String sanitize(String value) {
    if (value == null) {
      return "";
    }
    String trimmed = value.length() > 200 ? value.substring(0, 200) : value;
    return trimmed.replace('\r', '_').replace('\n', '_');
  }
}
