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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 钉钉机器人回复：经入站消息携带的 {@code sessionWebhook} POST markdown（对齐企微出站）；出站前过 {@link OutboundGuard}。
 *
 * <p>群聊 B4：{@code replyToMessageId} 非空时附带 {@code at.atUserIds} 引用提问者（会话内可对应）。
 */
public class DingTalkMessageSender {

  static final int DEFAULT_CHUNK_SIZE = 3500;
  static final String SESSION_WEBHOOK_PREFIX = "https://oapi.dingtalk.com";
  static final String MSG_TYPE_MARKDOWN = "markdown";
  static final String DEFAULT_MARKDOWN_TITLE = "OryxOS";
  private static final String MARKDOWN_HEADING_PREFIX = "#";
  private static final int MARKDOWN_TITLE_MAX_LEN = 20;
  private static final int HTTP_STATUS_OK_MIN = 200;
  private static final int HTTP_STATUS_OK_MAX_EXCLUSIVE = 300;
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String[] BUSINESS_CODE_FIELDS = {"errcode", "StatusCode", "code"};
  private static final String[] BUSINESS_MESSAGE_FIELDS = {"errmsg", "msg", "message"};

  private final HttpClient httpClient;
  private final OutboundGuard guard;
  private final int chunkSize;
  private final Map<String, String> sessionWebhooks = new ConcurrentHashMap<>();
  private final Map<String, String> groupAtUserIds = new ConcurrentHashMap<>();

  public DingTalkMessageSender(OutboundGuard guard, int chunkSize) {
    this(HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build(), guard, chunkSize);
  }

  DingTalkMessageSender(HttpClient httpClient, OutboundGuard guard, int chunkSize) {
    this.httpClient = httpClient;
    this.guard = guard;
    this.chunkSize = chunkSize <= 0 ? DEFAULT_CHUNK_SIZE : chunkSize;
  }

  /** 记录会话 webhook 与群聊 @ 目标（senderStaffId 优先）。 */
  public void rememberSession(String conversationId, String sessionWebhook, String atUserId) {
    if (conversationId != null && !conversationId.isBlank()) {
      if (sessionWebhook != null && !sessionWebhook.isBlank()) {
        sessionWebhooks.put(conversationId, sessionWebhook);
      }
      if (atUserId != null && !atUserId.isBlank()) {
        groupAtUserIds.put(conversationId, atUserId);
      }
    }
  }

  public void send(String conversationId, String text, String replyToMessageId) {
    String webhook = sessionWebhooks.get(conversationId);
    if (webhook == null || webhook.isBlank()) {
      throw new IllegalStateException("钉钉会话 " + conversationId + " 无 sessionWebhook，无法回复");
    }
    guard.check(webhook);
    String atUserId =
        replyToMessageId != null && !replyToMessageId.isBlank()
            ? groupAtUserIds.get(conversationId)
            : null;
    for (String chunk : segment(text == null ? "" : text, chunkSize)) {
      postMarkdown(webhook, chunk, atUserId);
    }
  }

  private void postMarkdown(String webhook, String content, String atUserId) {
    try {
      ObjectNode body = MAPPER.createObjectNode();
      body.put("msgtype", MSG_TYPE_MARKDOWN);
      ObjectNode markdown = body.putObject("markdown");
      markdown.put("title", markdownTitle(content));
      markdown.put("text", content);
      if (atUserId != null && !atUserId.isBlank()) {
        ObjectNode at = body.putObject("at");
        ArrayNode ids = MAPPER.createArrayNode();
        ids.add(atUserId);
        at.set("atUserIds", ids);
        at.put("isAtAll", false);
      }
      String payload = MAPPER.writeValueAsString(body);
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(webhook))
              .timeout(REQUEST_TIMEOUT)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(payload))
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < HTTP_STATUS_OK_MIN
          || response.statusCode() >= HTTP_STATUS_OK_MAX_EXCLUSIVE) {
        throw new IllegalStateException(
            "钉钉 sessionWebhook 回复失败 HTTP " + response.statusCode() + ": " + response.body());
      }
      rejectBusinessError(response.body(), webhook);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("钉钉 sessionWebhook 回复失败: " + e.getMessage(), e);
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

  /** 钉钉 markdown 必填 title：取首行摘要，过长截断；空内容用默认标题。 */
  static String markdownTitle(String content) {
    if (content == null || content.isBlank()) {
      return DEFAULT_MARKDOWN_TITLE;
    }
    String line = content.stripLeading();
    int newline = line.indexOf('\n');
    if (newline >= 0) {
      line = line.substring(0, newline);
    }
    line = line.strip();
    if (line.startsWith(MARKDOWN_HEADING_PREFIX)) {
      line = line.replaceFirst("^#+\\s*", "");
    }
    if (line.length() > MARKDOWN_TITLE_MAX_LEN) {
      line = line.substring(0, MARKDOWN_TITLE_MAX_LEN);
    }
    return line.isBlank() ? DEFAULT_MARKDOWN_TITLE : line;
  }

  /**
   * HTTP 2xx 仍可能带业务错误码（对齐 {@code NotifyPoster} / 飞书 SDK 口径，#316）。
   *
   * <p>缺字段、非 JSON 或非数字 code 不判失败。
   */
  static void rejectBusinessError(String responseBody, String webhook) {
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
    for (String field : BUSINESS_CODE_FIELDS) {
      JsonNode codeNode = root.get(field);
      if (codeNode == null || codeNode.isNull() || !codeNode.isValueNode()) {
        continue;
      }
      Long code = asBusinessCode(codeNode);
      if (code == null) {
        continue;
      }
      if (code != 0L) {
        throw new IllegalStateException(
            "钉钉 sessionWebhook 业务失败 "
                + field
                + "="
                + code
                + messageSuffix(root)
                + ": "
                + sanitizeUrl(webhook));
      }
      return;
    }
  }

  private static Long asBusinessCode(JsonNode node) {
    if (node.isNumber()) {
      return node.longValue();
    }
    if (node.isTextual()) {
      String text = node.asText().strip();
      if (text.isEmpty()) {
        return null;
      }
      try {
        return Long.parseLong(text);
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private static String messageSuffix(JsonNode root) {
    for (String field : BUSINESS_MESSAGE_FIELDS) {
      JsonNode msg = root.get(field);
      if (msg != null && msg.isTextual() && !msg.asText().isBlank()) {
        return " (" + msg.asText().strip() + ")";
      }
    }
    return "";
  }

  private static String sanitizeUrl(String url) {
    return url == null ? "" : url.replace('\r', '_').replace('\n', '_');
  }
}
