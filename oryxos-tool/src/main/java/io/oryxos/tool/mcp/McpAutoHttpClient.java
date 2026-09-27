package io.oryxos.tool.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpTransportException;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * SDK 0.18.3 没有结构化 HTTP 错误，auto 仅在第一次 POST 的响应边界判定兼容性。
 *
 * <p>只适配 SDK 已有的 JDK HTTP 接口；连接和业务调用仍走同步 MCP 客户端。不读取凭证、不解析异常文本，成功响应与后续请求原样交给 SDK。
 */
final class McpAutoHttpClient extends HttpClient {

  private static final int MAX_ERROR_BODY_BYTES = 16 * 1024;
  private final HttpClient delegate;
  private final AtomicBoolean firstPost = new AtomicBoolean(true);

  private McpAutoHttpClient(HttpClient delegate) {
    this.delegate = delegate;
  }

  static HttpClient.Builder builder() {
    return new AutoBuilder();
  }

  private <T> HttpResponse.BodyHandler<T> inspectFirstPost(
      HttpRequest request, HttpResponse.BodyHandler<T> handler) {
    if (!"POST".equals(request.method()) || !firstPost.compareAndSet(true, false)) {
      return handler;
    }
    return info -> {
      int status = info.statusCode();
      if (status >= 200 && status < 300) {
        return handler.apply(info);
      }
      HttpResponse.BodySubscriber<T> body =
          HttpResponse.BodySubscribers.mapping(
              HttpResponse.BodySubscribers.ofByteArray(),
              bytes -> {
                throw responseFailure(status, bytes);
              });
      return new LimitedErrorSubscriber<>(body);
    };
  }

  private static McpTransportException responseFailure(int status, byte[] body) {
    if (status != 400 && status != 404 && status != 405) {
      return new McpTransportException("MCP 初始化 HTTP 失败，不允许回退：" + status);
    }
    try {
      Object parsed = McpJsonDefaults.getMapper().readValue(body, Object.class);
      if (parsed instanceof Map<?, ?> message
          && "2.0".equals(message.get("jsonrpc"))
          && message.get("error") instanceof Map<?, ?>) {
        // 已经是 MCP 协议错误，不能将版本/方法/请求校验问题误当作旧传输。
        return new McpTransportException("MCP 初始化返回 JSON-RPC 错误，HTTP " + status);
      }
    } catch (IOException e) {
      // 空正文或非 JSON 正文仍可按 HTTP 兼容规则尝试 SSE；SSE endpoint 事件由 SDK 校验。
    }
    return new LegacyEndpointException(status);
  }

  static final class LegacyEndpointException extends McpTransportException {
    private static final long serialVersionUID = 1L;

    private LegacyEndpointException(int status) {
      super("MCP 初始化端点不接受 Streamable HTTP，HTTP " + status);
    }
  }

  private static final class LimitedErrorSubscriber<T> implements HttpResponse.BodySubscriber<T> {
    private final HttpResponse.BodySubscriber<T> delegate;
    private Optional<Flow.Subscription> subscription = Optional.empty();
    private long received;
    private boolean rejected;

    private LimitedErrorSubscriber(HttpResponse.BodySubscriber<T> delegate) {
      this.delegate = delegate;
    }

    @Override
    public CompletionStage<T> getBody() {
      return delegate.getBody();
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = Optional.of(subscription);
      delegate.onSubscribe(subscription);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      if (rejected) {
        return;
      }
      for (ByteBuffer buffer : buffers) {
        received += buffer.remaining();
      }
      if (received > MAX_ERROR_BODY_BYTES) {
        rejected = true;
        subscription.ifPresent(Flow.Subscription::cancel);
        delegate.onError(new McpTransportException("MCP 初始化错误正文过大，无法安全判定传输"));
        return;
      }
      delegate.onNext(buffers);
    }

    @Override
    public void onError(Throwable error) {
      if (!rejected) {
        delegate.onError(error);
      }
    }

    @Override
    public void onComplete() {
      if (!rejected) {
        delegate.onComplete();
      }
    }
  }

  @Override
  public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
      throws IOException, InterruptedException {
    return delegate.send(request, inspectFirstPost(request, handler));
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request, HttpResponse.BodyHandler<T> handler) {
    return delegate.sendAsync(request, inspectFirstPost(request, handler));
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request,
      HttpResponse.BodyHandler<T> handler,
      HttpResponse.PushPromiseHandler<T> pushHandler) {
    return delegate.sendAsync(request, inspectFirstPost(request, handler), pushHandler);
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

  @Override
  public void shutdown() {
    delegate.shutdown();
  }

  @Override
  public boolean awaitTermination(Duration duration) throws InterruptedException {
    return delegate.awaitTermination(duration);
  }

  @Override
  public boolean isTerminated() {
    return delegate.isTerminated();
  }

  @Override
  public void shutdownNow() {
    delegate.shutdownNow();
  }

  @Override
  public void close() {
    delegate.close();
  }

  private static final class AutoBuilder implements HttpClient.Builder {
    private final HttpClient.Builder delegate =
        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1);

    @Override
    public HttpClient.Builder cookieHandler(CookieHandler value) {
      delegate.cookieHandler(value);
      return this;
    }

    @Override
    public HttpClient.Builder connectTimeout(Duration value) {
      delegate.connectTimeout(value);
      return this;
    }

    @Override
    public HttpClient.Builder sslContext(SSLContext value) {
      delegate.sslContext(value);
      return this;
    }

    @Override
    public HttpClient.Builder sslParameters(SSLParameters value) {
      delegate.sslParameters(value);
      return this;
    }

    @Override
    public HttpClient.Builder executor(Executor value) {
      delegate.executor(value);
      return this;
    }

    @Override
    public HttpClient.Builder followRedirects(HttpClient.Redirect value) {
      delegate.followRedirects(value);
      return this;
    }

    @Override
    public HttpClient.Builder version(HttpClient.Version value) {
      delegate.version(value);
      return this;
    }

    @Override
    public HttpClient.Builder priority(int value) {
      delegate.priority(value);
      return this;
    }

    @Override
    public HttpClient.Builder proxy(ProxySelector value) {
      delegate.proxy(value);
      return this;
    }

    @Override
    public HttpClient.Builder authenticator(Authenticator value) {
      delegate.authenticator(value);
      return this;
    }

    @Override
    public HttpClient.Builder localAddress(InetAddress value) {
      delegate.localAddress(value);
      return this;
    }

    @Override
    public HttpClient build() {
      return new McpAutoHttpClient(delegate.build());
    }
  }
}
