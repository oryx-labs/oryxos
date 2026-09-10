package io.oryxos.channel.douyin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.OutboundGuard;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;

/** {@code POST https://open.douyin.com/im/send/msg/?open_id=} 场景一回复。 */
public class DouyinMessageSender {

  static final String API_BASE = "https://open.douyin.com";
  static final String SEND_PATH = "/im/send/msg/";
  private static final String SCENE_REPLY = "im_reply_msg";
  private static final int MSG_TYPE_TEXT = 1;
  private static final int HTTP_OK_MIN = 200;
  private static final int HTTP_OK_MAX_EXCLUSIVE = 300;
  private static final Duration TIMEOUT = Duration.ofSeconds(20);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient http;
  private final OutboundGuard guard;
  private final Supplier<String> accessToken;
  private final String operatorOpenId;

  public DouyinMessageSender(
      OutboundGuard guard, Supplier<String> accessToken, String operatorOpenId) {
    this(
        HttpClient.newBuilder().connectTimeout(TIMEOUT).build(),
        guard,
        accessToken,
        operatorOpenId);
  }

  DouyinMessageSender(
      HttpClient http, OutboundGuard guard, Supplier<String> accessToken, String operatorOpenId) {
    this.http = http;
    this.guard = guard;
    this.accessToken = accessToken;
    this.operatorOpenId = operatorOpenId;
  }

  public void sendReply(String toUserOpenId, String conversationId, String msgId, String text) {
    String token = accessToken.get();
    if (token == null || token.isBlank()) {
      throw new IllegalStateException("抖音 access_token 为空");
    }
    String url =
        API_BASE
            + SEND_PATH
            + "?open_id="
            + URLEncoder.encode(operatorOpenId, StandardCharsets.UTF_8);
    guard.check(url);
    try {
      ObjectNode body = MAPPER.createObjectNode();
      body.put("msg_id", msgId);
      body.put("conversation_id", conversationId);
      body.put("to_user_id", toUserOpenId);
      body.put("scene", SCENE_REPLY);
      ObjectNode content = body.putObject("content");
      content.put("msg_type", MSG_TYPE_TEXT);
      content.putObject("text").put("text", text == null ? "" : text);
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .timeout(TIMEOUT)
              .header("access-token", token)
              .header("Content-Type", "application/json; charset=utf-8")
              .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
              .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < HTTP_OK_MIN || response.statusCode() >= HTTP_OK_MAX_EXCLUSIVE) {
        throw new IllegalStateException(
            "抖音发私信失败 HTTP " + response.statusCode() + ": " + sanitize(response.body()));
      }
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("抖音发私信失败: " + e.getMessage(), e);
    }
  }

  private static String sanitize(String value) {
    if (value == null) {
      return "";
    }
    String trimmed = value.length() > 200 ? value.substring(0, 200) : value;
    return trimmed.replace('\r', '_').replace('\n', '_');
  }
}
