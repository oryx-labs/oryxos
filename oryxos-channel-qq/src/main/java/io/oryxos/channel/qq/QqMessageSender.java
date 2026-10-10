package io.oryxos.channel.qq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.OutboundGuard;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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

  /**
   * 单条消息上限（字符数）。
   *
   * <p>★ 这个值<b>不来自官方文档</b> —— QQ 官方没有公布消息长度上限：字段页对 {@code content} 只写「文本内容。{@code msg_type=0}
   * 时为全文」；错误码里有 {@code 40054007 消息长度超限} 与 {@code 40054018 消息过长或异常}，但都不给数字。
   *
   * <p>它来自 2026-10-10 的真机实测：
   *
   * <ul>
   *   <li><b>API 侧根本不拦</b> —— 单条 100 万字符也被接受（返回 {@code id}）。
   *   <li><b>真正的约束在客户端渲染</b>。HarmonyOS 版 QQ 上：15000 字符完整显示 （结尾标记可见）；16000 / 17000 / 20000
   *       看不到（被折叠或转成聊天记录卡片）。 ⇒ 边界落在 15000~16000。
   *   <li>故取 <b>15000</b> —— 实测确认能完整显示的最大值。取到边界而非留余量是刻意的： 它是唯一有实测支持的数字；比它小的值（如
   *       12000）里那点「余量」只是对其它客户端 （iOS / Android / PC）的推测，推测不比实测硬。
   *       <p>顺带：超限后的表现是<b>折叠 / 转聊天记录卡片</b>而不是报错，用户得手动点开才看得到， 所以这里宁可保守。
   */
  static final int DEFAULT_CHUNK_SIZE = 15000;

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
    return io.oryxos.core.channel.OutboundTextSegments.split(text, chunkSize);
  }

  private static boolean httpSuccess(int statusCode) {
    return statusCode >= HTTP_STATUS_OK_MIN && statusCode < HTTP_STATUS_OK_MAX_EXCLUSIVE;
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
