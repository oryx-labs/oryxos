package io.oryxos.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import io.oryxos.tool.sandbox.FileSandboxProperties;
import io.oryxos.tool.sandbox.HttpSandboxProperties;
import io.oryxos.tool.sandbox.PermissiveSandbox;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxViolationException;
import io.oryxos.tool.sandbox.ShellSandboxProperties;
import io.oryxos.tool.sandbox.WhitelistSandbox;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** 课件《第20节》验收 harness：HttpToolsTest——课件正文两用例即模板。 */
class HttpToolsTest {

  private HttpServer server;
  private final List<String> receivedBodies = new ArrayList<>();

  private final HttpTools tools = new HttpTools(new PermissiveSandbox(), RestClient.create());

  @BeforeEach
  void startFakeService() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/",
        exchange -> {
          receivedBodies.add(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] response = "{\"weather\":\"晴\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopFakeService() {
    server.stop(0);
  }

  private String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
  }

  @Test
  @DisplayName("http_get_应能取回响应")
  void httpGetReturnsResponseBody() {
    String result = tools.httpGet(url());

    assertTrue(result.contains("晴"));
  }

  @Test
  @DisplayName("http_post 提交 JSON body 并取回响应")
  void httpPostSubmitsBody() {
    String result = tools.httpPost(url(), "{\"city\":\"beijing\"}");

    assertTrue(result.contains("晴"));
    assertEquals("{\"city\":\"beijing\"}", receivedBodies.get(0));
  }

  @Test
  @DisplayName("http_get_命中白名单外域名应被拦下")
  void httpGetOutsideWhitelistIsBlocked() {
    Sandbox denying = mock(Sandbox.class);
    doThrow(new SandboxViolationException("域名不在白名单")).when(denying).enforce(any());
    HttpTools guarded = new HttpTools(denying, RestClient.create());

    assertThrows(RuntimeException.class, () -> guarded.httpGet(url())); // 课件断言形态
    assertEquals(0, receivedBodies.size(), "校验不过，请求根本不该发出");
  }

  @Test
  @DisplayName("白名单外域名_底层请求从未发出")
  void requestOutsideWhitelist_serverNeverReceives() {
    // 真 WhitelistSandbox（只允许 *.example.com），请求本地假服务（127.0.0.1）——域名不在白名单，
    // 断言假服务零收报文，证明白名单逻辑经工具接线真正拦住了对外 IO
    Sandbox whitelist =
        new WhitelistSandbox(
            new FileSandboxProperties(List.of()),
            new ShellSandboxProperties(List.of()),
            new HttpSandboxProperties(List.of("*.example.com")));
    HttpTools guarded = new HttpTools(whitelist, RestClient.create());

    assertThrows(SandboxViolationException.class, () -> guarded.httpGet(url()));
    assertEquals(0, receivedBodies.size(), "白名单外域名，请求根本不该到达服务");
  }
}
