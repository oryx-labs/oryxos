package io.oryxos.channel.dingtalk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DingTalkMessageSenderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private HttpServer server;
  private final List<String> bodies = new ArrayList<>();
  private final AtomicReference<String> guarded = new AtomicReference<>();

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/hook",
        exchange -> {
          bodies.add(readBody(exchange));
          respond(exchange, 200, "{\"errcode\":0}");
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("sessionWebhook 发送 markdown 并过 OutboundGuard")
  void sendMarkdownViaSessionWebhook() throws Exception {
    String webhookUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    DingTalkMessageSender sender =
        new DingTalkMessageSender(
            target -> guarded.set(target), DingTalkMessageSender.DEFAULT_CHUNK_SIZE);
    sender.rememberSession("conv-1", webhookUrl, null);
    sender.send("conv-1", "你好", null);

    assertEquals(webhookUrl, guarded.get());
    assertEquals(1, bodies.size());
    JsonNode body = MAPPER.readTree(bodies.get(0));
    assertEquals(DingTalkMessageSender.MSG_TYPE_MARKDOWN, body.path("msgtype").asText());
    assertEquals("你好", body.path("markdown").path("text").asText());
    assertEquals("你好", body.path("markdown").path("title").asText());
    assertTrue(body.path("at").isMissingNode());
  }

  @Test
  @DisplayName("markdown title 取首行并截断")
  void markdownTitleFromFirstLine() {
    assertEquals("OryxOS", DingTalkMessageSender.markdownTitle(""));
    assertEquals("摘要", DingTalkMessageSender.markdownTitle("## 摘要\n正文"));
    String title = DingTalkMessageSender.markdownTitle("这是一段很长的标题需要被截断到二十字以后的内容");
    assertTrue(title.length() <= 20);
    assertTrue(title.startsWith("这是一段很长的标题"));
  }

  @Test
  @DisplayName("群聊 replyToMessageId 非空时附带 atUserIds")
  void sendWithAtUser() throws Exception {
    String webhookUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    DingTalkMessageSender sender =
        new DingTalkMessageSender(target -> {}, DingTalkMessageSender.DEFAULT_CHUNK_SIZE);
    sender.rememberSession("conv-g", webhookUrl, "staff-9");
    sender.send("conv-g", "答", "msg-1");

    JsonNode body = MAPPER.readTree(bodies.get(0));
    assertEquals(DingTalkMessageSender.MSG_TYPE_MARKDOWN, body.path("msgtype").asText());
    assertEquals("staff-9", body.path("at").path("atUserIds").get(0).asText());
  }

  @Test
  @DisplayName("HTTP 200 + errcode≠0 业务失败上抛")
  void sendFailsOnBusinessErrcode() {
    server.createContext(
        "/bad",
        exchange -> {
          respond(exchange, 200, "{\"errcode\":310000,\"errmsg\":\"关键词不匹配\"}");
        });
    String webhookUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/bad";
    DingTalkMessageSender sender =
        new DingTalkMessageSender(target -> {}, DingTalkMessageSender.DEFAULT_CHUNK_SIZE);
    sender.rememberSession("conv-bad", webhookUrl, null);

    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> sender.send("conv-bad", "hi", null));
    assertTrue(ex.getMessage().contains("errcode=310000"));
  }

  @Test
  @DisplayName("HTTP 200 + errcode=0 视为成功")
  void sendSucceedsOnBusinessErrcodeZero() {
    server.createContext(
        "/ok",
        exchange -> {
          bodies.add(readBody(exchange));
          respond(exchange, 200, "{\"errcode\":0,\"errmsg\":\"ok\"}");
        });
    String webhookUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/ok";
    DingTalkMessageSender sender =
        new DingTalkMessageSender(target -> {}, DingTalkMessageSender.DEFAULT_CHUNK_SIZE);
    sender.rememberSession("conv-ok", webhookUrl, null);
    sender.send("conv-ok", "ok", null);
    assertEquals(1, bodies.size());
  }

  private static String readBody(HttpExchange exchange) throws IOException {
    return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
