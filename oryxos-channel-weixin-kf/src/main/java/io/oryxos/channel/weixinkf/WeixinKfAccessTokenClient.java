package io.oryxos.channel.weixinkf;

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

/** {@code GET /cgi-bin/gettoken}，按 expires_in 缓存。 */
final class WeixinKfAccessTokenClient {

  static final String API_BASE = "https://qyapi.weixin.qq.com";
  private static final Duration TIMEOUT = Duration.ofSeconds(20);
  private static final long SKEW_MS = 120_000L;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient http;
  private final OutboundGuard guard;
  private final String corpId;
  private final String secret;
  private final AtomicReference<Cached> cached = new AtomicReference<>();

  WeixinKfAccessTokenClient(OutboundGuard guard, String corpId, String secret) {
    this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), guard, corpId, secret);
  }

  WeixinKfAccessTokenClient(HttpClient http, OutboundGuard guard, String corpId, String secret) {
    this.http = http;
    this.guard = guard;
    this.corpId = corpId;
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
      String token = fetch();
      return token;
    }
  }

  private String fetch() {
    String url =
        API_BASE
            + "/cgi-bin/gettoken?corpid="
            + URLEncoder.encode(corpId, StandardCharsets.UTF_8)
            + "&corpsecret="
            + URLEncoder.encode(secret, StandardCharsets.UTF_8);
    guard.check(url);
    try {
      HttpRequest request =
          HttpRequest.newBuilder().uri(URI.create(url)).timeout(TIMEOUT).GET().build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "微信客服 gettoken HTTP " + response.statusCode() + ": " + sanitize(response.body()));
      }
      JsonNode root = MAPPER.readTree(response.body() == null ? "{}" : response.body());
      int errcode = root.path("errcode").asInt(0);
      if (errcode != 0) {
        throw new IllegalStateException(
            "微信客服 gettoken errcode=" + errcode + " errmsg=" + root.path("errmsg").asText());
      }
      String token = root.path("access_token").asText(null);
      if (token == null || token.isBlank()) {
        throw new IllegalStateException("微信客服 gettoken 未返回 access_token");
      }
      long expiresInSec = root.path("expires_in").asLong(7200L);
      long ttlMs = Math.max(60_000L, expiresInSec * 1000L - SKEW_MS);
      cached.set(new Cached(token, System.currentTimeMillis() + ttlMs));
      return token;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("微信客服 gettoken 失败: " + e.getMessage(), e);
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
