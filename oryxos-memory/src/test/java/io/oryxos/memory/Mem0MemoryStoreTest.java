package io.oryxos.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.oryxos.core.memory.MemoryScope;
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
import org.springframework.web.client.RestClientResponseException;

/** Mem0 档专属：mock 自托管 Mem0 的 REST（JDK HttpServer 假服务），不碰真 server。 */
class Mem0MemoryStoreTest {

  private record Received(String path, String body) {}

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private HttpServer server;
  private final List<Received> received = new ArrayList<>();
  private volatile int status = 200;
  private volatile String responseBody = "{\"results\":[]}";

  @BeforeEach
  void startFakeMem0() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          received.add(new Received(exchange.getRequestURI().getPath(), body));
          byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
          exchange.sendResponseHeaders(status, out.length);
          exchange.getResponseBody().write(out);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopFakeMem0() {
    server.stop(0);
  }

  private Mem0MemoryStore store() {
    String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    return new Mem0MemoryStore(RestClient.builder().baseUrl(baseUrl).build(), "agent-42");
  }

  @Test
  @DisplayName("append 发出的请求体带 content 与 scope metadata")
  void appendSendsContentAndScope() throws IOException {
    store().append("用户偏好深色主题", MemoryScope.CORE);

    Received req = received.get(0);
    assertTrue(req.path().contains("/v1/memories"));
    JsonNode body = MAPPER.readTree(req.body());
    assertEquals("用户偏好深色主题", body.get("messages").get(0).get("content").asText());
    assertEquals("CORE", body.get("metadata").get("scope").asText());
    assertEquals("agent-42", body.get("user_id").asText());
  }

  @Test
  @DisplayName("recall 转发查询并解析 memory 文本")
  void recallForwardsQueryAndParsesResults() {
    responseBody = "{\"results\":[{\"memory\":\"用户喜欢咖啡\"},{\"memory\":\"项目叫 OryxOS\"}]}";

    List<String> hits = store().recallByKeyword("用户");

    assertEquals(List.of("用户喜欢咖啡", "项目叫 OryxOS"), hits);
    assertTrue(received.get(0).path().contains("/search"));
  }

  @Test
  @DisplayName("Mem0 返回 5xx_异常上抛不静默吞")
  void serverErrorPropagates() {
    status = 500;

    assertThrows(
        RestClientResponseException.class, () -> store().append("x", MemoryScope.ARCHIVAL));
  }
}
