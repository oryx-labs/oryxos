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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Slack 回复：{@code chat.postMessage}；出站前过 {@link OutboundGuard}。
 *
 * <p>凭证：Bot Token（{@code xoxb-}）。群聊 {@code replyToMessageId} 非空时作为 {@code thread_ts}。
 */
public class SlackMessageSender {

  static final int DEFAULT_CHUNK_SIZE = 3500;
  static final String API_BASE_URL = "https://slack.com";
  static final String POST_MESSAGE_PATH = "/api/chat.postMessage";
  private static final int HTTP_STATUS_OK_MIN = 200;
  private static final int HTTP_STATUS_OK_MAX_EXCLUSIVE = 300;
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String FIELD_OK = "ok";
  private static final String FIELD_ERROR = "error";

  private final HttpClient httpClient;
  private final OutboundGuard guard;
  private final String botToken;
  private final int chunkSize;

  public SlackMessageSender(OutboundGuard guard, String botToken, int chunkSize) {
    this(
        HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build(),
        guard,
        botToken,
        chunkSize);
  }

  SlackMessageSender(HttpClient httpClient, OutboundGuard guard, String botToken, int chunkSize) {
    this.httpClient = httpClient;
    this.guard = guard;
    this.botToken = botToken;
    this.chunkSize = chunkSize <= 0 ? DEFAULT_CHUNK_SIZE : chunkSize;
  }

  public void send(String channelId, String text, String replyToMessageId) {
    guard.check(API_BASE_URL);
    for (String chunk : segment(text == null ? "" : text, chunkSize)) {
      postMessage(channelId, chunk, replyToMessageId);
    }
  }

  private void postMessage(String channelId, String text, String threadTs) {
    try {
      ObjectNode body = MAPPER.createObjectNode();
      body.put("channel", channelId);
      body.put("text", text);
      if (threadTs != null && !threadTs.isBlank()) {
        body.put("thread_ts", threadTs);
      }
      String payload = MAPPER.writeValueAsString(body);
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(API_BASE_URL + POST_MESSAGE_PATH))
              .timeout(REQUEST_TIMEOUT)
              .header("Authorization", "Bearer " + botToken)
              .header("Content-Type", "application/json; charset=utf-8")
              .POST(HttpRequest.BodyPublishers.ofString(payload))
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < HTTP_STATUS_OK_MIN
          || response.statusCode() >= HTTP_STATUS_OK_MAX_EXCLUSIVE) {
        throw new IllegalStateException(
            "Slack chat.postMessage 失败 HTTP "
                + response.statusCode()
                + ": "
                + sanitize(response.body()));
      }
      rejectBusinessError(response.body());
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Slack chat.postMessage 失败: " + e.getMessage(), e);
    }
  }

  static List<String> segment(String text, int chunkSize) {
    if (text.isEmpty()) {
      return List.of("");
    }
    List<String> parts = new ArrayList<>();
    for (int i = 0; i < text.length(); i += chunkSize) {
      parts.add(text.substring(i, Math.min(text.length(), i + chunkSize)));
    }
    return parts;
  }

  static void rejectBusinessError(String responseBody) {
    if (responseBody == null || responseBody.isBlank()) {
      return;
    }
    final JsonNode root;
    try {
      root = MAPPER.readTree(responseBody);
    } catch (JacksonException ignored) {
      return;
    }
    if (root == null || !root.isObject()) {
      return;
    }
    if (!root.path(FIELD_OK).asBoolean(false)) {
      throw new IllegalStateException(
          "Slack chat.postMessage 业务失败: " + sanitize(root.path(FIELD_ERROR).asText("unknown")));
    }
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
