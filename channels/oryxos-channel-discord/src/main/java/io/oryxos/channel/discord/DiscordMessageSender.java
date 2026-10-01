package io.oryxos.channel.discord;

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
 * Discord 回复：{@code POST /channels/{id}/messages}；出站前过 {@link OutboundGuard}。
 *
 * <p>凭证：Bot Token。群聊 {@code replyToMessageId} 非空时写入 {@code message_reference}。
 */
public class DiscordMessageSender {

  static final int DEFAULT_CHUNK_SIZE = 1900;
  static final String API_BASE_URL = "https://discord.com";
  static final String API_PREFIX = "/api/v10";
  private static final int HTTP_STATUS_OK_MIN = 200;
  private static final int HTTP_STATUS_OK_MAX_EXCLUSIVE = 300;
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String FIELD_CODE = "code";
  private static final String FIELD_MESSAGE = "message";

  private final HttpClient httpClient;
  private final OutboundGuard guard;
  private final String botToken;
  private final int chunkSize;

  public DiscordMessageSender(OutboundGuard guard, String botToken, int chunkSize) {
    this(
        HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build(),
        guard,
        botToken,
        chunkSize);
  }

  DiscordMessageSender(HttpClient httpClient, OutboundGuard guard, String botToken, int chunkSize) {
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

  private void postMessage(String channelId, String text, String replyToMessageId) {
    try {
      ObjectNode body = MAPPER.createObjectNode();
      body.put("content", text);
      if (replyToMessageId != null && !replyToMessageId.isBlank()) {
        ObjectNode ref = body.putObject("message_reference");
        ref.put("message_id", replyToMessageId);
        ref.put("fail_if_not_exists", false);
      }
      String payload = MAPPER.writeValueAsString(body);
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(API_BASE_URL + API_PREFIX + "/channels/" + channelId + "/messages"))
              .timeout(REQUEST_TIMEOUT)
              .header("Authorization", "Bot " + botToken)
              .header("Content-Type", "application/json; charset=utf-8")
              .POST(HttpRequest.BodyPublishers.ofString(payload))
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < HTTP_STATUS_OK_MIN
          || response.statusCode() >= HTTP_STATUS_OK_MAX_EXCLUSIVE) {
        rejectApiError(response.statusCode(), response.body());
      }
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Discord 发消息失败: " + e.getMessage(), e);
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

  static void rejectApiError(int statusCode, String responseBody) {
    String detail = sanitize(responseBody);
    if (responseBody != null && !responseBody.isBlank()) {
      try {
        JsonNode root = MAPPER.readTree(responseBody);
        if (root != null && root.isObject()) {
          String msg = root.path(FIELD_MESSAGE).asText(null);
          int code = root.path(FIELD_CODE).asInt(0);
          if (msg != null && !msg.isBlank()) {
            detail = sanitize(msg) + (code > 0 ? " (code=" + code + ")" : "");
          }
        }
      } catch (JacksonException ignored) {
        // keep raw body
      }
    }
    throw new IllegalStateException("Discord 发消息失败 HTTP " + statusCode + ": " + detail);
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
