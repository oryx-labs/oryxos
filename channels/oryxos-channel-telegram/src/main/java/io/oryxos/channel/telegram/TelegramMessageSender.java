package io.oryxos.channel.telegram;

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

/** Telegram {@code sendMessage}；群聊带 {@code reply_to_message_id}。 */
public class TelegramMessageSender {

  static final int DEFAULT_CHUNK_SIZE = 3500;
  private static final int HTTP_STATUS_OK_MIN = 200;
  private static final int HTTP_STATUS_OK_MAX_EXCLUSIVE = 300;
  private static final Duration TIMEOUT = Duration.ofSeconds(20);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String FIELD_OK = "ok";
  private static final String FIELD_RESULT = "result";
  private static final String FIELD_FILE_PATH = "file_path";
  private static final String FIELD_CHAT_ID = "chat_id";
  private static final String FIELD_TEXT = "text";
  private static final String FIELD_REPLY_TO_MESSAGE_ID = "reply_to_message_id";

  private final HttpClient http;
  private final OutboundGuard guard;
  private final String apiBase;
  private final String token;
  private final int chunkSize;

  public TelegramMessageSender(OutboundGuard guard, String apiBase, String token) {
    this(
        HttpClient.newBuilder().connectTimeout(TIMEOUT).build(),
        guard,
        apiBase,
        token,
        DEFAULT_CHUNK_SIZE);
  }

  TelegramMessageSender(
      HttpClient http, OutboundGuard guard, String apiBase, String token, int chunkSize) {
    this.http = http;
    this.guard = guard;
    this.apiBase = trimSlash(apiBase);
    this.token = token;
    this.chunkSize = chunkSize <= 0 ? DEFAULT_CHUNK_SIZE : chunkSize;
  }

  public void send(String chatId, String text, String replyToMessageId) {
    String url = apiBase + "/bot" + token + "/sendMessage";
    guard.check(url);
    for (String chunk : segment(text == null ? "" : text, chunkSize)) {
      post(url, chatId, chunk, replyToMessageId);
    }
  }

  /** {@code getFile} 把 file_id 换成可下载路径（相对 Telegram 文件 CDN）。 */
  public String resolveFileUrl(String fileId) {
    String url = apiBase + "/bot" + token + "/getFile?file_id=" + fileId;
    guard.check(url);
    try {
      HttpRequest request =
          HttpRequest.newBuilder().uri(URI.create(url)).timeout(TIMEOUT).GET().build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      JsonNode root = MAPPER.readTree(response.body());
      if (root == null || !root.path(FIELD_OK).asBoolean(false)) {
        throw new IllegalStateException("Telegram getFile 失败: " + sanitize(response.body()));
      }
      String path = root.path(FIELD_RESULT).path(FIELD_FILE_PATH).asText("");
      if (path.isBlank()) {
        throw new IllegalStateException("Telegram getFile 无 file_path");
      }
      String fileUrl = apiBase + "/file/bot" + token + "/" + path;
      guard.check(fileUrl);
      return fileUrl;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Telegram getFile 失败: " + e.getMessage(), e);
    }
  }

  private void post(String url, String chatId, String text, String replyToMessageId) {
    try {
      ObjectNode body = MAPPER.createObjectNode();
      body.put(FIELD_CHAT_ID, chatId);
      body.put(FIELD_TEXT, text);
      if (replyToMessageId != null && !replyToMessageId.isBlank()) {
        body.put(FIELD_REPLY_TO_MESSAGE_ID, Long.parseLong(replyToMessageId));
      }
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .timeout(TIMEOUT)
              .header("Content-Type", "application/json; charset=utf-8")
              .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
              .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      JsonNode root = MAPPER.readTree(response.body() == null ? "{}" : response.body());
      if (!httpSuccess(response.statusCode()) || businessFailed(root)) {
        throw new IllegalStateException("Telegram 发消息失败: " + sanitize(response.body()));
      }
    } catch (NumberFormatException e) {
      throw new IllegalStateException("Telegram reply_to_message_id 非法: " + replyToMessageId, e);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Telegram 发消息失败: " + e.getMessage(), e);
    }
  }

  private static boolean httpSuccess(int statusCode) {
    return statusCode >= HTTP_STATUS_OK_MIN && statusCode < HTTP_STATUS_OK_MAX_EXCLUSIVE;
  }

  private static boolean businessFailed(JsonNode root) {
    return root != null && root.has(FIELD_OK) && !root.path(FIELD_OK).asBoolean(true);
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

  private static String trimSlash(String base) {
    String s = base.strip();
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
