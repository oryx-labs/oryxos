package io.oryxos.web.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import io.oryxos.web.error.ProviderUnavailableException;
import io.oryxos.web.error.ResourceNotFoundException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
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
    server.stop(0);
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
}
