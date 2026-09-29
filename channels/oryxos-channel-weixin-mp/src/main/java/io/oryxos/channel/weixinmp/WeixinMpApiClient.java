package io.oryxos.channel.weixinmp;

import com.fasterxml.jackson.databind.JsonNode;
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

/** 服务号 {@code message/custom/send} 文本客服消息。 */
final class WeixinMpApiClient implements WeixinMpClient {

  static final String API_BASE = WeixinMpAccessTokenClient.API_BASE;

  private static final Duration TIMEOUT = Duration.ofSeconds(20);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int HTTP_OK_MIN = 200;
  private static final int HTTP_OK_MAX = 300;

  private final HttpClient http;
  private final OutboundGuard guard;
  private final Supplier<String> accessToken;

  WeixinMpApiClient(OutboundGuard guard, Supplier<String> accessToken) {
    this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), guard, accessToken);
  }

  WeixinMpApiClient(HttpClient http, OutboundGuard guard, Supplier<String> accessToken) {
    this.http = http;
    this.guard = guard;
    this.accessToken = accessToken;
  }

  @Override
  public void sendText(String openId, String text) {
    if (openId == null || openId.isBlank()) {
      throw new IllegalArgumentException("openId 为空");
    }
    ObjectNode body = MAPPER.createObjectNode();
    body.put("touser", openId);
    body.put("msgtype", "text");
    body.putObject("text").put("content", text == null ? "" : text);
    postJson("/cgi-bin/message/custom/send", body);
  }

  private JsonNode postJson(String path, ObjectNode body) {
    String token = accessToken.get();
    if (token == null || token.isBlank()) {
      throw new IllegalStateException("服务号 access_token 为空");
    }
    String url =
        API_BASE + path + "?access_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    guard.check(url);
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .timeout(TIMEOUT)
              .header("Content-Type", "application/json; charset=utf-8")
              .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
              .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < HTTP_OK_MIN || response.statusCode() >= HTTP_OK_MAX) {
        throw new IllegalStateException(
            "服务号 " + path + " HTTP " + response.statusCode() + ": " + sanitize(response.body()));
      }
      JsonNode root = MAPPER.readTree(response.body() == null ? "{}" : response.body());
      int errcode = root.path("errcode").asInt(0);
      if (errcode != 0) {
        throw new IllegalStateException(
            "服务号 " + path + " errcode=" + errcode + " errmsg=" + root.path("errmsg").asText());
      }
      return root;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("服务号 " + path + " 失败: " + e.getMessage(), e);
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
