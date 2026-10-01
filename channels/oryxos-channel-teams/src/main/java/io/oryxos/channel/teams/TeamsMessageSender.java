package io.oryxos.channel.teams;

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

/** Bot Framework 回复：client_credentials 换 token 后 POST {@code /v3/conversations/{id}/activities}。 */
public class TeamsMessageSender {

  static final String LOGIN_HOST = "https://login.microsoftonline.com";
  private static final int HTTP_STATUS_OK_MIN = 200;
  private static final int HTTP_STATUS_OK_MAX_EXCLUSIVE = 300;
  private static final Duration TIMEOUT = Duration.ofSeconds(20);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String FIELD_ACCESS_TOKEN = "access_token";

  private final HttpClient http;
  private final OutboundGuard guard;
  private final String appId;
  private final String appSecret;
  private final String tenantId;

  public TeamsMessageSender(OutboundGuard guard, String appId, String appSecret, String tenantId) {
    this(
        HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), guard, appId, appSecret, tenantId);
  }

  TeamsMessageSender(
      HttpClient http, OutboundGuard guard, String appId, String appSecret, String tenantId) {
    this.http = http;
    this.guard = guard;
    this.appId = appId;
    this.appSecret = appSecret;
    this.tenantId = tenantId;
  }

  public void send(String serviceUrl, String conversationId, String text, String replyToMessageId) {
    String token = fetchToken();
    String url =
        trimSlash(serviceUrl) + "/v3/conversations/" + urlEncode(conversationId) + "/activities";
    guard.check(url);
    try {
      ObjectNode body = MAPPER.createObjectNode();
      body.put("type", "message");
      body.put("text", text == null ? "" : text);
      if (replyToMessageId != null && !replyToMessageId.isBlank()) {
        body.put("replyToId", replyToMessageId);
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
        throw new IllegalStateException("Teams 发消息失败 HTTP " + response.statusCode());
      }
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Teams 发消息失败: " + e.getMessage(), e);
    }
  }

  private String fetchToken() {
    String url = LOGIN_HOST + "/" + tenantId + "/oauth2/v2.0/token";
    guard.check(url);
    String form =
        "grant_type=client_credentials&client_id="
            + urlEncode(appId)
            + "&client_secret="
            + urlEncode(appSecret)
            + "&scope="
            + urlEncode("https://api.botframework.com/.default");
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .timeout(TIMEOUT)
              .header("Content-Type", "application/x-www-form-urlencoded")
              .POST(HttpRequest.BodyPublishers.ofString(form))
              .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      JsonNode root = MAPPER.readTree(response.body() == null ? "{}" : response.body());
      String token = root.path(FIELD_ACCESS_TOKEN).asText("");
      if (token.isBlank()) {
        throw new IllegalStateException("Teams 换 token 失败");
      }
      return token;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Teams 换 token 失败: " + e.getMessage(), e);
    }
  }

  private static String trimSlash(String base) {
    String s = base.strip();
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }

  private static String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
