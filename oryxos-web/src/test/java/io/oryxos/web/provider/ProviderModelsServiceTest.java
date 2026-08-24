package io.oryxos.web.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import io.oryxos.web.error.ProviderUnavailableException;
import io.oryxos.web.error.ResourceNotFoundException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** ProviderModelsService 单元：mock 占位、真实 /models 解析、未知 provider→404、端点不可达→503。 */
class ProviderModelsServiceTest {

  private HttpServer server;
  private String baseUrl;
  private ProviderRegistry registry;
  private ProviderModelsService service;
  private final CountDownLatch releaseHang = new CountDownLatch(1);

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    registry = mock(ProviderRegistry.class);
    service = new ProviderModelsService(registry, RestClient.builder());
  }

  @AfterEach
  void tearDown() {
    releaseHang.countDown();
    server.stop(0);
    System.clearProperty(ProviderModelsService.READ_TIMEOUT_PROP);
  }

  @Test
  @DisplayName("mock provider_返回占位模型列表")
  void mock_returnsPlaceholder() {
    when(registry.find("mock")).thenReturn(Optional.of(new ProviderDef("mock", null, null, null)));

    assertEquals(List.of("mock"), service.listModels("mock"));
  }

  @Test
  @DisplayName("真实 provider_解析 /v1/models 并按字母排序返回 id（baseUrl 不含 /v1，符合新约定 fix-issue-47）")
  void real_parsesModelsSorted() {
    server.createContext(
        "/v1/models",
        exchange -> {
          byte[] body =
              "{\"object\":\"list\",\"data\":[{\"id\":\"gpt-4o\"},{\"id\":\"gpt-3.5-turbo\"}]}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    when(registry.find("openai"))
        .thenReturn(Optional.of(new ProviderDef("openai", "sk-x", baseUrl, null)));

    assertEquals(List.of("gpt-3.5-turbo", "gpt-4o"), service.listModels("openai"));
  }

  @Test
  @DisplayName("baseUrl 带末尾 /v1 → 剥离后仍拼 /v1/models（兼容用户误填，fix-issue-47）")
  void baseUrlWithV1_strippedThenAppendsV1Models() {
    server.createContext(
        "/v1/models",
        exchange -> {
          byte[] body = "{\"data\":[{\"id\":\"m1\"}]}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    when(registry.find("opencode"))
        .thenReturn(Optional.of(new ProviderDef("opencode", "sk-x", baseUrl + "/v1", null)));

    assertEquals(List.of("m1"), service.listModels("opencode"));
  }

  @Test
  @DisplayName("baseUrl 带末尾 / → 剥离后拼 /v1/models（fix-issue-47）")
  void baseUrlWithTrailingSlash_strippedThenAppendsV1Models() {
    server.createContext(
        "/v1/models",
        exchange -> {
          byte[] body = "{\"data\":[{\"id\":\"m2\"}]}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    when(registry.find("slash"))
        .thenReturn(Optional.of(new ProviderDef("slash", "sk-x", baseUrl + "/", null)));

    assertEquals(List.of("m2"), service.listModels("slash"));
  }

  @Test
  @DisplayName("未知 provider_抛 ResourceNotFoundException")
  void unknown_throwsNotFound() {
    when(registry.find("ghost")).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> service.listModels("ghost"));
  }

  @Test
  @DisplayName("端点不可达_抛 ProviderUnavailableException")
  void unreachable_throwsUnavailable() {
    when(registry.find("down"))
        .thenReturn(Optional.of(new ProviderDef("down", "sk-x", "http://127.0.0.1:1", null)));

    assertThrows(ProviderUnavailableException.class, () -> service.listModels("down"));
  }

  @Test
  @DisplayName("端点收到请求后挂死_listModels 在读取超时内失败而非永久阻塞")
  void hangingEndpointFailsWithinReadTimeout() {
    System.setProperty(ProviderModelsService.READ_TIMEOUT_PROP, "1");
    server.createContext(
        "/v1/models",
        exchange -> {
          try {
            releaseHang.await(30, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          exchange.close();
        });
    when(registry.find("hang"))
        .thenReturn(Optional.of(new ProviderDef("hang", "sk-x", baseUrl, null)));
    // 属性在构造时读取：重建带 1s read timeout 的 service
    ProviderModelsService timed = new ProviderModelsService(registry, RestClient.builder());

    assertTimeoutPreemptively(
        Duration.ofSeconds(10),
        () -> assertThrows(ProviderUnavailableException.class, () -> timed.listModels("hang")));
  }

  @Test
  @DisplayName("/models 遇到 302 不得自动跟随（防恶意 baseUrl 拐到内网/元数据）")
  void modelsDoesNotFollowRedirects() throws Exception {
    java.util.concurrent.atomic.AtomicInteger sinkHits =
        new java.util.concurrent.atomic.AtomicInteger();
    HttpServer sink = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try {
      sink.createContext(
          "/v1/models",
          exchange -> {
            sinkHits.incrementAndGet();
            byte[] body = "{\"data\":[{\"id\":\"stolen\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      sink.start();
      String sinkModels = "http://127.0.0.1:" + sink.getAddress().getPort() + "/v1/models";
      server.createContext(
          "/v1/models",
          exchange -> {
            exchange.getResponseHeaders().add("Location", sinkModels);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
          });
      when(registry.find("evil"))
          .thenReturn(Optional.of(new ProviderDef("evil", "sk-x", baseUrl, null)));

      try {
        List<String> models = service.listModels("evil");
        assertTrue(models.stream().noneMatch(id -> id.contains("stolen")), "即便不抛错也不得返回重定向目标体");
      } catch (ProviderUnavailableException expected) {
        // 3xx 被 RestClient 当成错误也算 fail-closed，可接受
      }
      assertEquals(0, sinkHits.get(), "不得跟随 Location 访问下一跳");
    } finally {
      sink.stop(0);
    }
  }
}
