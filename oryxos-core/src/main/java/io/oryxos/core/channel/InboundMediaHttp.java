package io.oryxos.core.channel;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * 入站媒体 HTTP：默认 {@link HttpClient.Redirect#NEVER}，避免白名单仅验首次 URL 后被 302 打到内网。
 *
 * <p>若需跟随重定向，使用 {@link #getFollowingAllowlist}：每跳重验 Location host。
 */
public final class InboundMediaHttp {

  private static final int HTTP_STATUS_OK_MIN = 200;
  private static final int HTTP_STATUS_OK_MAX_EXCLUSIVE = 300;
  private static final int HTTP_STATUS_REDIRECT_MIN = 300;
  private static final int HTTP_STATUS_REDIRECT_MAX_EXCLUSIVE = 400;
  private static final int MAX_REDIRECTS = 5;
  private static final String HEADER_LOCATION = "Location";

  private InboundMediaHttp() {}

  public static HttpClient newNoRedirectClient(Duration connectTimeout) {
    return HttpClient.newBuilder()
        .connectTimeout(connectTimeout == null ? Duration.ofSeconds(60) : connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  /** GET 媒体；不跟随重定向。3xx 直接失败（调用方应改用 {@link #getFollowingAllowlist}）。 */
  public static HttpResponse<byte[]> getNoRedirect(
      HttpClient client, URI uri, Duration requestTimeout) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(uri)
            .timeout(requestTimeout == null ? Duration.ofSeconds(60) : requestTimeout)
            .GET()
            .build();
    HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    int code = response.statusCode();
    if (code >= HTTP_STATUS_REDIRECT_MIN && code < HTTP_STATUS_REDIRECT_MAX_EXCLUSIVE) {
      throw new IllegalStateException("媒体下载收到重定向 HTTP " + code + "（已禁用自动跟随；请使用 allowlist 逐跳校验）");
    }
    if (code < HTTP_STATUS_OK_MIN || code >= HTTP_STATUS_OK_MAX_EXCLUSIVE) {
      throw new IllegalStateException("下载临时文件 HTTP " + code);
    }
    return response;
  }

  /** GET 并最多跟随 {@value #MAX_REDIRECTS} 次；每一跳的 URI 须通过 {@code uriAllowed}。 */
  public static HttpResponse<byte[]> getFollowingAllowlist(
      HttpClient client, URI start, Duration requestTimeout, Predicate<URI> uriAllowed)
      throws Exception {
    if (start == null || uriAllowed == null) {
      throw new IllegalArgumentException("uri/allowlist 不可空");
    }
    URI current = start;
    Duration timeout = requestTimeout == null ? Duration.ofSeconds(60) : requestTimeout;
    for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
      if (!uriAllowed.test(current)) {
        throw new IllegalStateException(
            "拒绝非允许域临时下载地址: " + InboundMediaPaths.sanitizeLog(hostOf(current)));
      }
      HttpRequest request = HttpRequest.newBuilder().uri(current).timeout(timeout).GET().build();
      HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
      int code = response.statusCode();
      if (code >= HTTP_STATUS_OK_MIN && code < HTTP_STATUS_OK_MAX_EXCLUSIVE) {
        return response;
      }
      if (code >= HTTP_STATUS_REDIRECT_MIN && code < HTTP_STATUS_REDIRECT_MAX_EXCLUSIVE) {
        Optional<URI> next = resolveRedirect(current, response);
        if (next.isEmpty()) {
          throw new IllegalStateException("下载临时文件重定向缺 Location HTTP " + code);
        }
        current = next.get();
        continue;
      }
      throw new IllegalStateException("下载临时文件 HTTP " + code);
    }
    throw new IllegalStateException("媒体下载重定向超过上限 " + MAX_REDIRECTS);
  }

  private static Optional<URI> resolveRedirect(URI current, HttpResponse<?> response) {
    Optional<String> loc =
        response.headers().firstValue(HEADER_LOCATION).filter(s -> s != null && !s.isBlank());
    if (loc.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(current.resolve(loc.get().strip()));
    } catch (IllegalArgumentException | IllegalStateException e) {
      return Optional.empty();
    }
  }

  private static String hostOf(URI uri) {
    if (uri == null || uri.getHost() == null) {
      return "";
    }
    return uri.getHost().toLowerCase(Locale.ROOT);
  }
}
