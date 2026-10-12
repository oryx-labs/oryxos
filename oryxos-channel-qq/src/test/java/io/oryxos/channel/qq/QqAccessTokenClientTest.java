package io.oryxos.channel.qq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.oryxos.core.channel.OutboundGuard;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 覆盖 {@code access_token} 的取用这一层。
 *
 * <p><b>为什么要在 HTTP 层拦截</b>：{@link QqAccessTokenClient#API_BASE_URL} 是硬编码的 {@code
 * https://api.bot.qq.com}，而项目纪律不为测试改生产代码的构造签名（同 {@code QqMessageSenderTest} 的说明）。
 * 所以测试不去改那个常量，而是注入一个把 URI 重写到本地 {@link HttpServer} 的 {@link HttpClient} —— 请求按原样发出，只换目标地址。
 *
 * <p><b>这组测试钉住的是与同族渠道的一致性</b>：{@code weixin-kf} / {@code weixin-mp} / {@code weixin-mini} 三个 token
 * 客户端都检查 HTTP 状态码并在错误里带上它，QQ 这份原先没有 —— 于是 token 获取失败时只有 body、没有状态码，排障时分不清 401（凭证错）与 5xx（服务端故障）。
 */
class QqAccessTokenClientTest {

  private HttpServer server;
  private final List<String> paths = new ArrayList<>();

  /** 由用例改写的响应；默认是一个正常的 token 响应。 */
  private volatile int status = 200;

  private volatile String body = "{\"access_token\":\"tok-1\",\"expires_in\":7200}";

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        QqAccessTokenClient.TOKEN_PATH,
        exchange -> {
          paths.add(exchange.getRequestURI().getPath());
          byte[] out = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, out.length);
          exchange.getResponseBody().write(out);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  /** 真实的 guard 由装配层适配沙箱；这里只关心 URL 被校验过。 */
  private static final OutboundGuard NO_GUARD = url -> {};

  private QqAccessTokenClient client() {
    return new QqAccessTokenClient(
        new RedirectingHttpClient("http://127.0.0.1:" + server.getAddress().getPort()),
        NO_GUARD,
        "app-id",
        "client-secret");
  }

  @Test
  @DisplayName("非 2xx 抛错并在消息里带上状态码 —— 对齐同族 token 客户端")
  void nonSuccessStatusIsReportedWithItsCode() {
    status = 401;
    body = "{\"code\":100007,\"message\":\"invalid appid or secret\"}";

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> client().accessToken());

    assertTrue(
        thrown.getMessage().contains("QQ getAppAccessToken HTTP 401"),
        "错误消息应点名状态码，实际是: " + thrown.getMessage());
  }

  @Test
  @DisplayName("5xx 同样被拦下并带上状态码")
  void serverErrorIsReportedWithItsCode() {
    status = 503;
    body = "";

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> client().accessToken());

    assertTrue(thrown.getMessage().contains("HTTP 503"), "实际是: " + thrown.getMessage());
  }

  @Test
  @DisplayName("2xx 返回 token，且第二次调用命中缓存不再打端点")
  void cachesTheToken() {
    QqAccessTokenClient client = client();

    assertEquals("tok-1", client.accessToken());
    assertEquals("tok-1", client.accessToken());
    assertEquals(1, paths.size(), "第二次取 token 应命中缓存，不应再次请求");
  }

  @Test
  @DisplayName("鉴权头是 'QQBot <token>'")
  void authorizationHeaderFormat() {
    assertEquals("QQBot tok-1", client().authorizationHeader());
  }

  @Test
  @DisplayName("2xx 但没有 access_token 时点名报错（原行为不回归）")
  void missingTokenIsStillReported() {
    body = "{\"expires_in\":7200}";

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> client().accessToken());

    assertTrue(thrown.getMessage().contains("无 access_token"), "实际是: " + thrown.getMessage());
  }

  @Test
  @DisplayName("expires_in 为字符串时按数字解析（官方两种形态都出现）")
  void expiresInAsTextIsParsed() {
    body = "{\"access_token\":\"tok-text\",\"expires_in\":\"7200\"}";

    assertEquals("tok-text", client().accessToken());
  }

  @Test
  @DisplayName("expires_in 缺失或非数字时退回默认有效期，不抛错")
  void missingExpiresInFallsBackToDefault() {
    body = "{\"access_token\":\"tok-no-exp\"}";
    assertEquals("tok-no-exp", client().accessToken());

    body = "{\"access_token\":\"tok-bad-exp\",\"expires_in\":\"soon\"}";
    assertEquals("tok-bad-exp", client().accessToken());
  }

  /**
   * 把请求重写到本地测试服务器的 {@link HttpClient}。
   *
   * <p>保留 method / headers / body，只替换 URI —— 生产代码里那个硬编码域名因此不必为测试让路。 只有 {@code send} 会被 {@link
   * QqAccessTokenClient} 用到，其余方法一律委托或抛 {@link UnsupportedOperationException}。
   */
  private static final class RedirectingHttpClient extends HttpClient {

    private final HttpClient delegate = HttpClient.newHttpClient();
    private final String origin;

    RedirectingHttpClient(String origin) {
      this.origin = origin;
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
        throws IOException, InterruptedException {
      HttpRequest rewritten =
          HttpRequest.newBuilder(request, (name, value) -> true)
              .uri(URI.create(origin + request.uri().getPath()))
              .build();
      return delegate.send(rewritten, handler);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> handler,
        HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
      return delegate.cookieHandler();
    }

    @Override
    public Optional<Duration> connectTimeout() {
      return delegate.connectTimeout();
    }

    @Override
    public Redirect followRedirects() {
      return delegate.followRedirects();
    }

    @Override
    public Optional<ProxySelector> proxy() {
      return delegate.proxy();
    }

    @Override
    public SSLContext sslContext() {
      return delegate.sslContext();
    }

    @Override
    public SSLParameters sslParameters() {
      return delegate.sslParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
      return delegate.authenticator();
    }

    @Override
    public Version version() {
      return delegate.version();
    }

    @Override
    public Optional<Executor> executor() {
      return delegate.executor();
    }
  }
}
