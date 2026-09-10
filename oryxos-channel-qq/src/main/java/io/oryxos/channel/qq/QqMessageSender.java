package io.oryxos.channel.qq;

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
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * QQ 发信：群 {@code POST /v2/groups/{group_openid}/messages}；单聊 {@code POST
 * /v2/users/{user_openid}/messages}。
 *
 * <p>被动回复带 {@code msg_id} + 递增 {@code msg_seq}；{@code msg_id} 过期时降级为无引用主动发一次。
 */
public class QqMessageSender {

  static final int DEFAULT_CHUNK_SIZE = 2000;
  static final String API_BASE_URL = QqAccessTokenClient.API_BASE_URL;
  private static final int HTTP_STATUS_OK_MIN = 200;
  private static final int HTTP_STATUS_OK_MAX_EXCLUSIVE = 300;
  private static final Duration TIMEOUT = Duration.ofSeconds(20);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String FIELD_MSG_TYPE = "msg_type";
  private static final String FIELD_CONTENT = "content";
  private static final String FIELD_MSG_ID = "msg_id";
  private static final String FIELD_MSG_SEQ = "msg_seq";

  private final HttpClient http;
  private final OutboundGuard guard;
  private final QqAccessTokenClient tokens;
  private final int chunkSize;
  private final ConcurrentHashMap<String, AtomicInteger> msgSeqById = new ConcurrentHashMap<>();

  public QqMessageSender(OutboundGuard guard, QqAccessTokenClient tokens) {
    this(
        HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), guard, tokens, DEFAULT_CHUNK_SIZE);
  }

  QqMessageSender(HttpClient http, OutboundGuard guard, QqAccessTokenClient tokens, int chunkSize) {
    this.http = http;
    this.guard = guard;
    this.tokens = tokens;
    this.chunkSize = chunkSize <= 0 ? DEFAULT_CHUNK_SIZE : chunkSize;
  }

  public void send(String chatId, String text, String replyToMessageId) {
    guard.check(API_BASE_URL);
    String openid = QqChatTargets.openid(chatId);
    boolean group = QqChatTargets.isGroup(chatId);
    String path =
        group ? "/v2/groups/" + openid + "/messages" : "/v2/users/" + openid + "/messages";
    String url = API_BASE_URL + path;
    for (String chunk : segment(text == null ? "" : text, chunkSize)) {
      post(url, chunk, replyToMessageId, true);
    }
  }

  private void post(String url, String text, String replyToMessageId, boolean allowFallback) {
    try {
      ObjectNode body = MAPPER.createObjectNode();
      body.put(FIELD_MSG_TYPE, 0);
      body.put(FIELD_CONTENT, text);
      if (replyToMessageId != null && !replyToMessageId.isBlank()) {
        body.put(FIELD_MSG_ID, replyToMessageId);
        body.put(FIELD_MSG_SEQ, nextSeq(replyToMessageId));
      }
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .timeout(TIMEOUT)
              .header("Authorization", tokens.authorizationHeader())
              .header("Content-Type", "application/json; charset=utf-8")
              .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
              .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (httpSuccess(response.statusCode())) {
        return;
      }
      String respBody = response.body() == null ? "" : response.body();
      if (allowFallback
          && replyToMessageId != null
          && !replyToMessageId.isBlank()
          && isMsgIdExpired(respBody)) {
        post(url, text, null, false);
        return;
      }
      throw new IllegalStateException("QQ 发消息失败: " + sanitize(respBody));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("QQ 发消息失败: " + e.getMessage(), e);
    }
  }

  private int nextSeq(String msgId) {
    return msgSeqById.computeIfAbsent(msgId, ignored -> new AtomicInteger(1)).getAndIncrement();
  }

  static boolean isMsgIdExpired(String body) {
    if (body == null || body.isBlank()) {
      return false;
    }
    String lower = body.toLowerCase(Locale.ROOT);
    return body.contains("msg_id已过期")
        || body.contains("msg_id 已过期")
        || lower.contains("msg_id expired")
        || lower.contains("msgid expired");
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

  private static boolean httpSuccess(int statusCode) {
    return statusCode >= HTTP_STATUS_OK_MIN && statusCode < HTTP_STATUS_OK_MAX_EXCLUSIVE;
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
