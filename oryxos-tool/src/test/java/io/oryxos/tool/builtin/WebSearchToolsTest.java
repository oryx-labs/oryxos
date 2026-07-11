package io.oryxos.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import io.oryxos.tool.sandbox.PermissiveSandbox;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxViolationException;
import io.oryxos.tool.web.DuckDuckGoSearchProvider;
import io.oryxos.tool.web.SearchProvider;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** web_search 工具 + DuckDuckGo provider 测试（假搜索服务，不碰真网）。 */
class WebSearchToolsTest {

  private HttpServer server;

  @BeforeEach
  void startFakeSearch() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/",
        exchange -> {
          String json =
              "{\"RelatedTopics\":[{\"Text\":\"OryxOS 是企业级 Agent OS\","
                  + "\"FirstURL\":\"https://oryxos.example/intro\"}]}";
          byte[] body = json.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopFakeSearch() {
    server.stop(0);
  }

  private String endpoint() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
  }

  @Test
  @DisplayName("web_search 渲染标题/链接/摘要")
  void webSearchRendersResults() {
    SearchProvider provider = new DuckDuckGoSearchProvider(RestClient.create(), endpoint());
    WebSearchTools tools = new WebSearchTools(new PermissiveSandbox(), provider);

    String result = tools.webSearch("oryxos");

    assertTrue(result.contains("企业级 Agent OS"));
    assertTrue(result.contains("https://oryxos.example/intro"));
  }

  @Test
  @DisplayName("无结果时返回明确提示")
  void webSearchNoResult() {
    SearchProvider empty = query -> List.of();
    WebSearchTools tools = new WebSearchTools(new PermissiveSandbox(), empty);

    assertEquals("（未搜到相关结果）", tools.webSearch("zzz"));
  }

  @Test
  @DisplayName("越界会被拦：白名单拒绝时不发起搜索")
  void sandboxRejectionBlocksSearch() {
    Sandbox denying = mock(Sandbox.class);
    doThrow(new SandboxViolationException("搜索域名不在白名单")).when(denying).enforce(any());
    SearchProvider provider = mock(SearchProvider.class);
    WebSearchTools tools = new WebSearchTools(denying, provider);

    assertThrows(SandboxViolationException.class, () -> tools.webSearch("x"));
  }
}
