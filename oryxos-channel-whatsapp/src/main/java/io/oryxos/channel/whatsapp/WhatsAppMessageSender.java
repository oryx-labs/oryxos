package io.oryxos.channel.whatsapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.OutboundGuard;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/** Graph {@code /{phone-number-id}/messages} 文本回复。 */
public class WhatsAppMessageSender {

  static final String DEFAULT_GRAPH_BASE = "https://graph.facebook.com/v21.0";

  /** 单条文本消息正文上限（Cloud API 为 4096 字符），留余量。 */
  static final int DEFAULT_CHUNK_SIZE = 3500;

  private static final int HTTP_STATUS_OK_MIN = 200;
  private static final int HTTP_STATUS_OK_MAX_EXCLUSIVE = 300;
  private static final Duration TIMEOUT = Duration.ofSeconds(20);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient http;
  private final OutboundGuard guard;
  private final String graphBase;
  private final String token;
  private final String phoneNumberId;

  public WhatsAppMessageSender(OutboundGuard guard, String token, String phoneNumberId) {
    this(
        HttpClient.newBuilder().connectTimeout(TIMEOUT).build(),
        guard,
        DEFAULT_GRAPH_BASE,
        token,
        phoneNumberId);
  }

  WhatsAppMessageSender(
      HttpClient http, OutboundGuard guard, String graphBase, String token, String phoneNumberId) {
    this.http = http;
    this.guard = guard;
    this.graphBase = trimSlash(graphBase);
    this.token = token;
    this.phoneNumberId = phoneNumberId;
  }

  /** 逐段发送。Cloud API 单条文本消息的正文上限是 4096 字符，超了整条会被拒；留出余量。 */
  public void send(String to, String text) {
    for (String chunk : segment(text == null ? "" : text, DEFAULT_CHUNK_SIZE)) {
      postMessage(to, chunk);
    }
  }

  private void postMessage(String to, String text) {
    String url = graphBase + "/" + phoneNumberId + "/messages";
    guard.check(url);
    try {
      ObjectNode body = MAPPER.createObjectNode();
      body.put("messaging_product", "whatsapp");
      body.put("to", to);
      body.put("type", "text");
      body.putObject("text").put("body", text == null ? "" : text);
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
        throw new IllegalStateException("WhatsApp 发消息失败 HTTP " + response.statusCode());
      }
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("WhatsApp 发消息失败: " + e.getMessage(), e);
    }
  }

  private static String trimSlash(String base) {
    String s = base.strip();
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }

  static List<String> segment(String text, int chunkSize) {
    return io.oryxos.core.channel.OutboundTextSegments.split(text, chunkSize);
  }
}
