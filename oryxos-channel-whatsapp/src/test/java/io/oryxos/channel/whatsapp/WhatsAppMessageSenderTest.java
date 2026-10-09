package io.oryxos.channel.whatsapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WhatsAppMessageSenderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private HttpServer server;
  private final List<String> bodies = new ArrayList<>();

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/phone-1/messages",
        exchange -> {
          bodies.add(readBody(exchange));
          respond(exchange, 200, "{\"messages\":[{\"id\":\"wamid.1\"}]}");
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  private static String readBody(HttpExchange exchange) throws IOException {
    return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private WhatsAppMessageSender senderAgainst(HttpClient http) {
    return new WhatsAppMessageSender(
        http, url -> {}, "http://127.0.0.1:" + server.getAddress().getPort(), "tok", "phone-1");
  }

  /** 平台单条正文上限（Cloud API 单条文本消息正文）。 */
  private static final int PLATFORM_CAP = 4096;

  @Test
  @DisplayName("默认分段上限不超过平台单条上限")
  void defaultChunkSizeFitsUnderThePlatformCap() {
    assertTrue(
        WhatsAppMessageSender.DEFAULT_CHUNK_SIZE <= PLATFORM_CAP,
        "默认分段 " + WhatsAppMessageSender.DEFAULT_CHUNK_SIZE + " 超过平台上限 " + PLATFORM_CAP);
    assertTrue(WhatsAppMessageSender.DEFAULT_CHUNK_SIZE > 0);
  }

  @Test
  @DisplayName("超长回复被切成多段，每段不超上限，拼接后一字不差")
  void longReplyIsSplitAndReassembles() {
    String reply = "字".repeat(PLATFORM_CAP * 2 + 137);
    List<String> parts =
        WhatsAppMessageSender.segment(reply, WhatsAppMessageSender.DEFAULT_CHUNK_SIZE);
    assertTrue(parts.size() >= 3, "应切成多段，实际 " + parts.size());
    for (String part : parts) {
      assertTrue(
          part.length() <= WhatsAppMessageSender.DEFAULT_CHUNK_SIZE, "有段超上限: " + part.length());
    }
    assertEquals(reply, String.join("", parts), "分段拼接必须与原回复一致");
  }

  @Test
  @DisplayName("短回复只切一段，不额外分包")
  void shortReplyStaysWhole() {
    assertEquals(
        List.of("你好"),
        WhatsAppMessageSender.segment("你好", WhatsAppMessageSender.DEFAULT_CHUNK_SIZE));
  }

  @Test
  @DisplayName("空文本仍产出一段（保持「必有回复」语义）")
  void emptyYieldsOneEmptyPart() {
    assertEquals(
        List.of(""), WhatsAppMessageSender.segment("", WhatsAppMessageSender.DEFAULT_CHUNK_SIZE));
    assertEquals(
        List.of(""), WhatsAppMessageSender.segment(null, WhatsAppMessageSender.DEFAULT_CHUNK_SIZE));
  }

  @Test
  @DisplayName("超长回复按段发多次请求，拼接后与原回复一致")
  void longReplyPostsOncePerChunk() throws Exception {
    String reply = "字".repeat(WhatsAppMessageSender.DEFAULT_CHUNK_SIZE * 2 + 10);
    senderAgainst(HttpClient.newHttpClient()).send("8613800000000", reply);

    assertEquals(3, bodies.size(), "应发 3 次请求，实际 " + bodies.size());
    StringBuilder joined = new StringBuilder();
    for (String body : bodies) {
      joined.append(MAPPER.readTree(body).path("text").path("body").asText());
    }
    assertEquals(reply, joined.toString(), "各段拼接必须与原回复一致");
  }

  @Test
  @DisplayName("短回复只发一次请求")
  void shortReplyPostsOnce() throws Exception {
    senderAgainst(HttpClient.newHttpClient()).send("8613800000000", "你好");
    assertEquals(1, bodies.size());
    assertEquals("你好", MAPPER.readTree(bodies.get(0)).path("text").path("body").asText());
  }
}
