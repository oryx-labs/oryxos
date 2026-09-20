package io.oryxos.provider;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 超时回归：LLM 端点挂死时，单次 HTTP 调用必须在读取超时内失败，不能无限阻塞。
 *
 * <p>Spring AI 2.0 使用 openai-java OkHttp；此处用与工厂相同的 {@link
 * ProviderChatModelFactory#requestTimeout()} 装配 OkHttpClient，验证读超时生效（避免叠 ChatModel 协议层干扰单测预算）。
 */
class ProviderChatModelFactoryTimeoutTest {

  private HttpServer hangingServer;
  private final CountDownLatch release = new CountDownLatch(1);

  @BeforeEach
  void startHangingServer() throws Exception {
    hangingServer = HttpServer.create(new InetSocketAddress(0), 0);
    hangingServer.createContext(
        "/",
        exchange -> {
          try {
            release.await(30, TimeUnit.SECONDS); // 收到请求后既不响应也不断连——模拟挂死端点
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          exchange.close();
        });
    hangingServer.start();
  }

  @AfterEach
  void stopServer() {
    release.countDown(); // 放行 handler，避免 stop 等待
    hangingServer.stop(0);
    System.clearProperty(ProviderChatModelFactory.READ_TIMEOUT_PROP);
    System.clearProperty(ProviderChatModelFactory.CONNECT_TIMEOUT_PROP);
  }

  @Test
  @DisplayName("端点收到请求后挂死_单次调用在读取超时内失败而非永久阻塞")
  void hangingEndpointFailsWithinReadTimeout() {
    System.setProperty(ProviderChatModelFactory.READ_TIMEOUT_PROP, "1");
    System.setProperty(ProviderChatModelFactory.CONNECT_TIMEOUT_PROP, "1");
    String baseUrl = "http://127.0.0.1:" + hangingServer.getAddress().getPort() + "/";
    var timeout = ProviderChatModelFactory.requestTimeout();
    OkHttpClient client =
        new OkHttpClient.Builder()
            .connectTimeout(timeout.connect())
            .readTimeout(timeout.read())
            .writeTimeout(timeout.write())
            .callTimeout(timeout.request())
            .followRedirects(false)
            .build();

    assertTimeoutPreemptively(
        Duration.ofSeconds(10),
        () ->
            assertThrows(
                Exception.class,
                () -> client.newCall(new Request.Builder().url(baseUrl).get().build()).execute()));
  }
}
