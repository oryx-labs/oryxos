package io.oryxos.channel.gchat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.OutboundGuard;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/** Chat API {@code POST /v1/{space}/messages}。 */
public class GoogleChatMessageSender {

  static final String API_BASE = "https://chat.googleapis.com/v1/";

  /** 单条消息正文上限（Chat API 为 4096 字符），留余量。 */
  static final int DEFAULT_CHUNK_SIZE = 3500;

  private static final int HTTP_STATUS_OK_MIN = 200;
  private static final int HTTP_STATUS_OK_MAX_EXCLUSIVE = 300;
  private static final Duration TIMEOUT = Duration.ofSeconds(20);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient http;
  private final OutboundGuard guard;
  private final String accessToken;

  public GoogleChatMessageSender(OutboundGuard guard, String accessToken) {
    this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), guard, accessToken);
  }

  GoogleChatMessageSender(HttpClient http, OutboundGuard guard, String accessToken) {
    this.http = http;
    this.guard = guard;
    this.accessToken = accessToken;
  }

  /** 逐段发送。Chat API 单条消息的正文上限是 4096 字符，超了整条会被拒；留出与同族渠道一致的余量。 */
  public void send(String spaceName, String text, String replyToMessageId) {
    for (String chunk : segment(text == null ? "" : text, DEFAULT_CHUNK_SIZE)) {
      postMessage(spaceName, chunk, replyToMessageId);
    }
  }

  private void postMessage(String spaceName, String text, String replyToMessageId) {
    String url = API_BASE + spaceName + "/messages";
    guard.check(url);
    try {
      ObjectNode body = MAPPER.createObjectNode();
      body.put("text", text == null ? "" : text);
      if (replyToMessageId != null && !replyToMessageId.isBlank()) {
        body.putObject("thread").put("name", replyToMessageId);
      }
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .timeout(TIMEOUT)
              .header("Authorization", "Bearer " + accessToken)
              .header("Content-Type", "application/json; charset=utf-8")
              .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
              .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < HTTP_STATUS_OK_MIN
          || response.statusCode() >= HTTP_STATUS_OK_MAX_EXCLUSIVE) {
        throw new IllegalStateException("Google Chat 发消息失败 HTTP " + response.statusCode());
      }
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Google Chat 发消息失败: " + e.getMessage(), e);
    }
  }

  static List<String> segment(String text, int chunkSize) {
    return io.oryxos.core.channel.OutboundTextSegments.split(text, chunkSize);
  }
}
