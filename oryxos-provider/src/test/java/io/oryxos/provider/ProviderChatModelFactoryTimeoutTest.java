package io.oryxos.provider;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * 超时回归：LLM 端点挂死时，单次 HTTP 调用必须在读取超时内失败，不能无限阻塞。
 *
 * <p>直接测 {@link ProviderChatModelFactory#timeoutFactory()} 装配出的客户端（buildOne 传给 OpenAiApi 的就是它）：走
 * ChatModel.call 会叠加 Spring AI 默认 RetryTemplate（对 ResourceAccessException 重试 10 次、 指数退避至
 * 180s），无法在单测时间预算内断言。
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
  }

  @Test
  @DisplayName("端点收到请求后挂死_单次调用在读取超时内失败而非永久阻塞")
  void hangingEndpointFailsWithinReadTimeout() {
    System.setProperty(ProviderChatModelFactory.READ_TIMEOUT_PROP, "1");
    String baseUrl = "http://127.0.0.1:" + hangingServer.getAddress().getPort();
    RestClient client =
        RestClient.builder().requestFactory(ProviderChatModelFactory.timeoutFactory()).build();

    // 修复前：默认请求工厂无读取超时，这里会永久阻塞（preemptive 兜底 10 秒防测试挂死）
    assertTimeoutPreemptively(
        Duration.ofSeconds(10),
        () ->
            assertThrows(
                ResourceAccessException.class,
                () -> client.get().uri(baseUrl).retrieve().toEntity(String.class)));
  }
}
