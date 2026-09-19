package io.oryxos.core.channel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
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
 *
 * <p>企微 COS 等场景优先 {@link #getBytesFollowingAllowlist}（{@code HttpURLConnection} 的 connect/read
 * 超时更可靠；JDK {@code HttpClient} 在部分挂死链路上可能拖过 {@code request.timeout}）。
 */
public final class InboundMediaHttp {

  private static final int HTTP_STATUS_OK_MIN = 200;
  private static final int HTTP_STATUS_OK_MAX_EXCLUSIVE = 300;
  private static final int HTTP_STATUS_REDIRECT_MIN = 300;
  private static final int HTTP_STATUS_REDIRECT_MAX_EXCLUSIVE = 400;
  private static final int MAX_REDIRECTS = 5;
  private static final String HEADER_LOCATION = "Location";
  private static final String HEADER_USER_AGENT = "User-Agent";
  private static final String USER_AGENT = "OryxOS-InboundMedia/1.0";
  private static final int READ_BUFFER = 8192;
  private static final int DEFAULT_TIMEOUT_MS = 60_000;

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

  public static byte[] getBytesFollowingAllowlist(
      URI start,
      Duration connectTimeout,
      Duration readTimeout,
      long maxBytes,
      Predicate<URI> uriAllowed)
      throws IOException {
    return getBytesFollowingAllowlist(
        start, connectTimeout, readTimeout, maxBytes, uriAllowed, null);
  }

  /**
   * 同 {@link #getBytesFollowingAllowlist(URI, Duration, Duration, long, Predicate)}，可附带请求头（如 Slack
   * {@code Authorization: Bearer xoxb-}）。
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "URLCONNECTION_SSRF_FD",
      justification =
          "每跳 openConnection 前均经调用方 Predicate uriAllowed 校验；"
              + "setInstanceFollowRedirects(false)，Location 解析后下一跳再验，不自动跟跳到内网。")
  public static byte[] getBytesFollowingAllowlist(
      URI start,
      Duration connectTimeout,
      Duration readTimeout,
      long maxBytes,
      Predicate<URI> uriAllowed,
      java.util.Map<String, String> requestHeaders)
      throws IOException {
    if (start == null || uriAllowed == null) {
      throw new IllegalArgumentException("uri/allowlist 不可空");
    }
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes 必须为正");
    }
    int connectMs = timeoutMillis(connectTimeout);
    int readMs = timeoutMillis(readTimeout);
    URI current = start;
    for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
      if (!uriAllowed.test(current)) {
        throw new IOException("拒绝非允许域临时下载地址: " + InboundMediaPaths.sanitizeLog(hostOf(current)));
      }
      HttpURLConnection conn = openGet(current, connectMs, readMs, requestHeaders);
      try {
        int code = conn.getResponseCode();
        if (code >= HTTP_STATUS_OK_MIN && code < HTTP_STATUS_OK_MAX_EXCLUSIVE) {
          return readLimitedBody(conn, maxBytes);
        }
        if (code >= HTTP_STATUS_REDIRECT_MIN && code < HTTP_STATUS_REDIRECT_MAX_EXCLUSIVE) {
          String loc = conn.getHeaderField(HEADER_LOCATION);
          if (loc == null || loc.isBlank()) {
            throw new IOException("下载临时文件重定向缺 Location HTTP " + code);
          }
          try {
            current = current.resolve(loc.strip());
          } catch (IllegalArgumentException | IllegalStateException e) {
            throw new IOException("下载临时文件重定向 Location 非法 HTTP " + code, e);
          }
          continue;
        }
        throw new IOException("下载临时文件 HTTP " + code);
      } finally {
        conn.disconnect();
      }
    }
    throw new IOException("媒体下载重定向超过上限 " + MAX_REDIRECTS);
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "URLCONNECTION_SSRF_FD",
      justification =
          "仅由 getBytesFollowingAllowlist 在 uriAllowed 通过后调用；禁自动重定向，"
              + "避免 SpotBugs 将已校验 URI 的 openConnection 判为未防护 SSRF。")
  private static HttpURLConnection openGet(
      URI uri, int connectMs, int readMs, java.util.Map<String, String> requestHeaders)
      throws IOException {
    URL url;
    try {
      url = uri.toURL();
    } catch (IllegalArgumentException | java.net.MalformedURLException e) {
      throw new IOException("非法下载地址: " + InboundMediaPaths.sanitizeLog(hostOf(uri)), e);
    }
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setInstanceFollowRedirects(false);
    conn.setConnectTimeout(connectMs);
    conn.setReadTimeout(readMs);
    conn.setRequestMethod("GET");
    conn.setRequestProperty(HEADER_USER_AGENT, USER_AGENT);
    if (requestHeaders != null) {
      for (var e : requestHeaders.entrySet()) {
        if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null) {
          conn.setRequestProperty(e.getKey(), e.getValue());
        }
      }
    }
    conn.setUseCaches(false);
    return conn;
  }

  private static byte[] readLimitedBody(HttpURLConnection conn, long maxBytes) throws IOException {
    try (InputStream in = conn.getInputStream()) {
      ByteArrayOutputStream buf =
          new ByteArrayOutputStream(Math.min(READ_BUFFER * 8, (int) Math.min(maxBytes, 1 << 20)));
      byte[] chunk = new byte[READ_BUFFER];
      long total = 0;
      int n;
      while ((n = in.read(chunk)) >= 0) {
        total += n;
        if (total > maxBytes) {
          throw new IOException("入站文件超过上限 " + maxBytes + " 字节");
        }
        buf.write(chunk, 0, n);
      }
      if (total == 0) {
        throw new IOException("下载临时文件为空");
      }
      return buf.toByteArray();
    }
  }

  private static int timeoutMillis(Duration timeout) {
    if (timeout == null) {
      return DEFAULT_TIMEOUT_MS;
    }
    long ms = timeout.toMillis();
    if (ms <= 0) {
      return DEFAULT_TIMEOUT_MS;
    }
    if (ms > Integer.MAX_VALUE) {
      return Integer.MAX_VALUE;
    }
    return (int) ms;
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
