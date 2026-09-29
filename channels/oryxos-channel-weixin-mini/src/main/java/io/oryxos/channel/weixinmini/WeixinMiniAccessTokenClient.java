package io.oryxos.channel.weixinmini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.channel.OutboundGuard;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/** {@code GET /cgi-bin/token}，按 expires_in 缓存。 */
final class WeixinMiniAccessTokenClient {

  static final String API_BASE = "https://api.weixin.qq.com";
  private static final Duration TIMEOUT = Duration.ofSeconds(20);
  private static final long SKEW_MS = 120_000L;
  private static final int HTTP_OK_MIN = 200;
  private static final int HTTP_OK_MAX_EXCLUSIVE = 300;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient http;
  private final OutboundGuard guard;
  private final String appId;
  private final String secret;
  private final AtomicReference<Cached> cached = new AtomicReference<>();

  WeixinMiniAccessTokenClient(OutboundGuard guard, String appId, String secret) {
    this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), guard, appId, secret);
  }

  WeixinMiniAccessTokenClient(HttpClient http, OutboundGuard guard, String appId, String secret) {
    this.http = http;
    this.guard = guard;
    this.appId = appId;
    this.secret = secret;
  }

  String getToken() {
    Cached hit = cached.get();
    long now = System.currentTimeMillis();
    if (hit != null && now < hit.expiresAtMs()) {
      return hit.token();
    }
    synchronized (this) {
      hit = cached.get();
      now = System.currentTimeMillis();
      if (hit != null && now < hit.expiresAtMs()) {
        return hit.token();
      }
      return fetch();
    }
  }

  private String fetch() {
    String url =
        API_BASE
            + "/cgi-bin/token?grant_type=client_credential&appid="
            + URLEncoder.encode(appId, StandardCharsets.UTF_8)
            + "&secret="
            + URLEncoder.encode(secret, StandardCharsets.UTF_8);
    guard.check(url);
    try {
      HttpRequest request =
          HttpRequest.newBuilder().uri(URI.create(url)).timeout(TIMEOUT).GET().build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < HTTP_OK_MIN || response.statusCode() >= HTTP_OK_MAX_EXCLUSIVE) {
        throw new IllegalStateException(
            "小程序 token HTTP " + response.statusCode() + ": " + sanitize(response.body()));
      }
      JsonNode root = MAPPER.readTree(response.body() == null ? "{}" : response.body());
      int errcode = root.path("errcode").asInt(0);
      if (errcode != 0) {
        throw new IllegalStateException(
            "小程序 token errcode=" + errcode + " errmsg=" + root.path("errmsg").asText());
      }
      String token = root.path("access_token").asText(null);
      if (token == null || token.isBlank()) {
        throw new IllegalStateException("小程序 token 未返回 access_token");
      }
      long expiresInSec = root.path("expires_in").asLong(7200L);
      long ttlMs = Math.max(60_000L, expiresInSec * 1000L - SKEW_MS);
      cached.set(new Cached(token, System.currentTimeMillis() + ttlMs));
      return token;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("小程序 token 失败: " + e.getMessage(), e);
    }
  }

  private static String sanitize(String value) {
    if (value == null) {
      return "";
    }
    String trimmed = value.length() > 200 ? value.substring(0, 200) : value;
    return trimmed.replace('\r', '_').replace('\n', '_');
  }

  private record Cached(String token, long expiresAtMs) {}
}
