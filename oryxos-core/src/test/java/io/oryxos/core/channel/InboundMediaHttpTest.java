package io.oryxos.core.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InboundMediaHttpTest {

  private HttpServer server;
  private String base;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger hits = new AtomicInteger();
    server.createContext(
        "/ok",
        ex -> {
          hits.incrementAndGet();
          byte[] body = "IMG".getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(200, body.length);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
          }
        });
    server.createContext(
        "/redir",
        ex -> {
          hits.incrementAndGet();
          ex.getResponseHeaders().add("Location", base + "/ok");
          ex.sendResponseHeaders(302, -1);
          ex.close();
        });
    server.createContext(
        "/evil-redir",
        ex -> {
          ex.getResponseHeaders().add("Location", "http://127.0.0.1:9/internal");
          ex.sendResponseHeaders(302, -1);
          ex.close();
        });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("NEVER 客户端遇 302 不自动跟随")
  void noRedirectClientRejects302() {
    HttpClient client = InboundMediaHttp.newNoRedirectClient(Duration.ofSeconds(5));
    Exception ex =
        assertThrows(
            Exception.class,
            () ->
                InboundMediaHttp.getNoRedirect(
                    client, URI.create(base + "/redir"), Duration.ofSeconds(5)));
    assertTrue(ex.getMessage().contains("重定向") || ex.getMessage().contains("302"));
  }

  @Test
  @DisplayName("allowlist 逐跳跟随同主机 302")
  void followAllowlistSameHost() throws Exception {
    HttpClient client = InboundMediaHttp.newNoRedirectClient(Duration.ofSeconds(5));
    HttpResponse<byte[]> resp =
        InboundMediaHttp.getFollowingAllowlist(
            client,
            URI.create(base + "/redir"),
            Duration.ofSeconds(5),
            uri -> "127.0.0.1".equals(uri.getHost()));
    assertEquals(200, resp.statusCode());
    assertEquals("IMG", new String(resp.body(), StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("重定向到非白名单 host 拒绝")
  void followDenyOffAllowlist() {
    HttpClient client = InboundMediaHttp.newNoRedirectClient(Duration.ofSeconds(5));
    int port = server.getAddress().getPort();
    Exception ex =
        assertThrows(
            Exception.class,
            () ->
                InboundMediaHttp.getFollowingAllowlist(
                    client,
                    URI.create(base + "/evil-redir"),
                    Duration.ofSeconds(5),
                    uri ->
                        "127.0.0.1".equals(uri.getHost())
                            && uri.getPort() == port
                            && uri.getPath() != null
                            && (uri.getPath().equals("/evil-redir")
                                || uri.getPath().equals("/ok"))));
    assertTrue(ex.getMessage().contains("拒绝") || ex.getMessage().contains("非允许"));
  }
}
