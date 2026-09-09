package io.oryxos.channel.mattermost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.OutboundGuard;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Mattermost {@code POST /api/v4/posts}。 */
public class MattermostMessageSender {

  private static final int HTTP_STATUS_OK_MIN = 200;
  private static final int HTTP_STATUS_OK_MAX_EXCLUSIVE = 300;
  private static final Duration TIMEOUT = Duration.ofSeconds(20);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient http;
  private final OutboundGuard guard;
  private final String baseUrl;
  private final String token;

  public MattermostMessageSender(OutboundGuard guard, String baseUrl, String token) {
    this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), guard, baseUrl, token);
  }

  MattermostMessageSender(HttpClient http, OutboundGuard guard, String baseUrl, String token) {
    this.http = http;
    this.guard = guard;
    this.baseUrl = trimSlash(baseUrl);
    this.token = token;
  }

  public void send(String channelId, String text, String replyToMessageId) {
    String url = baseUrl + "/api/v4/posts";
    guard.check(url);
    try {
      ObjectNode body = MAPPER.createObjectNode();
      body.put("channel_id", channelId);
      body.put("message", text == null ? "" : text);
      String rootId = resolveThreadRootId(replyToMessageId);
      if (rootId != null && !rootId.isBlank()) {
        body.put("root_id", rootId);
      }
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .timeout(TIMEOUT)
              .header("Authorization", "Bearer " + token)
              .header("Content-Type", "application/json; charset=utf-8")
              .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
              .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < HTTP_STATUS_OK_MIN
          || response.statusCode() >= HTTP_STATUS_OK_MAX_EXCLUSIVE) {
        String errorBody = response.body() == null ? "" : response.body().strip();
        if (errorBody.length() > 200) {
          errorBody = errorBody.substring(0, 200);
        }
        throw new IllegalStateException(
            "Mattermost 发消息失败 HTTP "
                + response.statusCode()
                + (errorBody.isEmpty() ? "" : ": " + errorBody));
      }
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Mattermost 发消息失败: " + e.getMessage(), e);
    }
  }

  /**
   * Outgoing Webhook 只给当前帖 {@code post_id}。线程里回帖的 id 不能当 {@code root_id}，须解析到根帖；查不到则不跟帖，避免 HTTP
   * 400。
   */
  private String resolveThreadRootId(String postId) {
    if (postId == null || postId.isBlank()) {
      return null;
    }
    String url = baseUrl + "/api/v4/posts/" + postId;
    guard.check(url);
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .timeout(TIMEOUT)
              .header("Authorization", "Bearer " + token)
              .GET()
              .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < HTTP_STATUS_OK_MIN
          || response.statusCode() >= HTTP_STATUS_OK_MAX_EXCLUSIVE) {
        return null;
      }
      JsonNode node = MAPPER.readTree(response.body());
      String root = node.path("root_id").asText("");
      return root.isBlank() ? postId : root;
    } catch (Exception e) {
      return null;
    }
  }

  static String trimSlash(String base) {
    String s = base.strip();
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }
}
