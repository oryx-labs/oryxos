package io.oryxos.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import io.oryxos.tool.sandbox.ActionType;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
  @DisplayName("http_request GET 应转发自定义 headers（不得静默丢弃）")
  void httpRequestGetForwardsCustomHeaders() throws IOException {
    List<String> seenAuth = new ArrayList<>();
    HttpServer authServer = HttpServer.create(new InetSocketAddress(0), 0);
    authServer.createContext(
        "/secure",
        exchange -> {
          seenAuth.add(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    authServer.start();
    try {
      String target = "http://127.0.0.1:" + authServer.getAddress().getPort() + "/secure";
      String body =
          tools.httpRequest("GET", target, "Authorization: Bearer test-token\nX-Trace: 1", null);

      assertEquals("ok", body);
      assertEquals(List.of("Bearer test-token"), seenAuth, "GET 必须带上 Authorization");
    } finally {
      authServer.stop(0);
    }
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

  @Test
  @DisplayName("http_post 白名单内入口 302 到白名单外应被拦下（写路径逐跳复检）")
  void httpPostRedirectOutsideWhitelistIsBlocked() throws IOException {
    // 入口用 localhost（在白名单），Location 跳到 127.0.0.1（同机不同主机名、不在白名单）。
    // 修复前：写路径只校验首跳、RestClient 自动跟随 → 会打到外站；修复后：每跳 HTTP_REQUEST 复检。
    HttpServer entry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    HttpServer sink = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger sinkHits = new AtomicInteger();
    try {
      sink.createContext(
          "/",
          exchange -> {
            sinkHits.incrementAndGet();
            byte[] response = "leaked".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
          });
      String sinkUrl = "http://127.0.0.1:" + sink.getAddress().getPort() + "/sink";
      entry.createContext(
          "/",
          exchange -> {
            exchange.getResponseHeaders().add("Location", sinkUrl);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
          });
      entry.start();
      sink.start();

      Sandbox whitelist =
          new WhitelistSandbox(
              new FileSandboxProperties(List.of()),
              new ShellSandboxProperties(List.of()),
              new HttpSandboxProperties(List.of("localhost")));
      HttpTools guarded = new HttpTools(whitelist, RestClient.create());
      String start = "http://localhost:" + entry.getAddress().getPort() + "/";

      assertThrows(SandboxViolationException.class, () -> guarded.httpPost(start, "{\"x\":1}"));
      assertEquals(0, sinkHits.get(), "重定向目标不在白名单，请求不该到达 sink");
    } finally {
      entry.stop(0);
      sink.stop(0);
    }
  }

  @Test
  @DisplayName("http_request 跨源 302 不得把 Authorization 带到下一跳（白名单内主机亦然）")
  void httpRequestCrossOriginRedirectStripsAuthorization() throws IOException {
    HttpServer entry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    HttpServer sink = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger sinkHits = new AtomicInteger();
    List<String> sinkAuth = new ArrayList<>();
    List<String> sinkTrace = new ArrayList<>();
    try {
      sink.createContext(
          "/",
          exchange -> {
            sinkHits.incrementAndGet();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth != null) {
              sinkAuth.add(auth);
            }
            String trace = exchange.getRequestHeaders().getFirst("X-Trace-Id");
            if (trace != null) {
              sinkTrace.add(trace);
            }
            byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
          });
      String sinkUrl = "http://127.0.0.1:" + sink.getAddress().getPort() + "/sink";
      entry.createContext(
          "/",
          exchange -> {
            exchange.getResponseHeaders().add("Location", sinkUrl);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
          });
      entry.start();
      sink.start();

      // 两台都在白名单：#111 不会拦；修复前 Authorization 会跟到 sink
      Sandbox whitelist =
          new WhitelistSandbox(
              new FileSandboxProperties(List.of()),
              new ShellSandboxProperties(List.of()),
              new HttpSandboxProperties(List.of("localhost", "127.0.0.1")));
      HttpTools guarded = new HttpTools(whitelist, RestClient.create());
      String start = "http://localhost:" + entry.getAddress().getPort() + "/";

      String body =
          guarded.httpRequest(
              "POST",
              start,
              "Authorization: Bearer secret-token\nX-Trace-Id: keep-me",
              "{\"x\":1}");

      assertEquals("ok", body);
      assertEquals(1, sinkHits.get());
      assertTrue(sinkAuth.isEmpty(), "跨源重定向不得转发 Authorization");
      assertEquals(List.of("keep-me"), sinkTrace, "非敏感自定义头仍可转发");
    } finally {
      entry.stop(0);
      sink.stop(0);
    }
  }

  @Test
  @DisplayName("http_request 跨源 302 不得把 POST body 带到下一跳（改 GET）")
  void httpRequest302DoesNotReplayPostBody() throws IOException {
    HttpServer entry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    HttpServer sink = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    List<String> sinkMethods = new ArrayList<>();
    List<String> sinkBodies = new ArrayList<>();
    try {
      sink.createContext(
          "/",
          exchange -> {
            sinkMethods.add(exchange.getRequestMethod());
            sinkBodies.add(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
          });
      String sinkUrl = "http://127.0.0.1:" + sink.getAddress().getPort() + "/sink";
      entry.createContext(
          "/",
          exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location", sinkUrl);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
          });
      entry.start();
      sink.start();

      Sandbox whitelist =
          new WhitelistSandbox(
              new FileSandboxProperties(List.of()),
              new ShellSandboxProperties(List.of()),
              new HttpSandboxProperties(List.of("localhost", "127.0.0.1")));
      HttpTools guarded = new HttpTools(whitelist, RestClient.create());
      String start = "http://localhost:" + entry.getAddress().getPort() + "/";

      String body = guarded.httpRequest("POST", start, null, "{\"secret\":\"token\"}");

      assertEquals("ok", body);
      assertEquals(List.of("GET"), sinkMethods, "302 下一跳应改为 GET");
      assertEquals(List.of(""), sinkBodies, "302 不得把 POST body 转到下一跳");
    } finally {
      entry.stop(0);
      sink.stop(0);
    }
  }

  @Test
  @DisplayName("http_request 跨源 307 应保留 POST 与 body")
  void httpRequest307PreservesPostBody() throws IOException {
    HttpServer entry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    HttpServer sink = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    List<String> sinkMethods = new ArrayList<>();
    List<String> sinkBodies = new ArrayList<>();
    try {
      sink.createContext(
          "/",
          exchange -> {
            sinkMethods.add(exchange.getRequestMethod());
            sinkBodies.add(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
          });
      String sinkUrl = "http://127.0.0.1:" + sink.getAddress().getPort() + "/sink";
      entry.createContext(
          "/",
          exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location", sinkUrl);
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
          });
      entry.start();
      sink.start();

      Sandbox whitelist =
          new WhitelistSandbox(
              new FileSandboxProperties(List.of()),
              new ShellSandboxProperties(List.of()),
              new HttpSandboxProperties(List.of("localhost", "127.0.0.1")));
      HttpTools guarded = new HttpTools(whitelist, RestClient.create());
      String start = "http://localhost:" + entry.getAddress().getPort() + "/";

      String body = guarded.httpRequest("POST", start, null, "{\"keep\":true}");

      assertEquals("ok", body);
      assertEquals(List.of("POST"), sinkMethods, "307 应保留 POST");
      assertEquals(List.of("{\"keep\":true}"), sinkBodies, "307 应保留 body");
    } finally {
      entry.stop(0);
      sink.stop(0);
    }
  }

  @Test
  @DisplayName("download_file 落盘前复检 FILE_WRITE（防拉网窗口内路径逃逸）")
  void downloadFileRechecksPathBeforeWrite(@TempDir Path dir) throws IOException {
    AtomicInteger fileWrites = new AtomicInteger();
    Path nested = dir.resolve("nested");
    Path target = nested.resolve("payload.bin");
    Sandbox sandbox =
        action -> {
          if (action.type() == ActionType.FILE_WRITE) {
            int n = fileWrites.incrementAndGet();
            if (n >= 2) {
              // 复检必须在 createDirectories 之后：此时父目录应已存在
              assertTrue(Files.isDirectory(nested), "写前复检应发生在 createDirectories 之后");
              throw new SandboxViolationException("复检拒绝: " + action.target());
            }
          }
        };
    HttpTools guarded = new HttpTools(sandbox, RestClient.create());

    assertThrows(
        SandboxViolationException.class, () -> guarded.downloadFile(url(), target.toString()));
    assertEquals(2, fileWrites.get(), "应在下载前与 Files.write 前各 enforce 一次 FILE_WRITE");
    assertTrue(Files.notExists(target), "复检拒绝后不得落盘");
  }

  @Test
  @DisplayName("download_file 两次路径校验都通过时正常落盘")
  void downloadFileWritesWhenPathStillAllowed(@TempDir Path dir) throws IOException {
    Path target = dir.resolve("ok.bin");
    HttpTools permissive = new HttpTools(new PermissiveSandbox(), RestClient.create());

    String result = permissive.downloadFile(url(), target.toString());

    assertTrue(result.contains("已下载到"));
    assertTrue(Files.exists(target));
    assertTrue(Files.readString(target).contains("晴"));
  }
}
