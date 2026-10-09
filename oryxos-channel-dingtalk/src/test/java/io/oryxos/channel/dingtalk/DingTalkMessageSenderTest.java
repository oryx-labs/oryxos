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
    sender.rememberSession("conv-1", webhookUrl, null, "msg-auto");
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
    sender.rememberSession("conv-g", webhookUrl, "staff-9", "msg-1");
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
    sender.rememberSession("conv-bad", webhookUrl, null, "msg-auto");

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
    sender.rememberSession("conv-ok", webhookUrl, null, "msg-auto");
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

  @Test
  @DisplayName("业务失败时日志里不得出现 sessionWebhook 的会话能力令牌")
  void businessFailureDoesNotLeakTheSessionToken() {
    String webhook =
        "https://oapi.dingtalk.com/robot/sendBySession?session=66d7c695a9e1f18782a7cba1d9deb885";
    String body = "{\"errcode\":310000,\"errmsg\":\"keywords not in content\"}";

    IllegalStateException e =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () -> DingTalkMessageSender.rejectBusinessError(body, webhook));

    // 异常文案会被 safeReply / 进度流兜底打进 WARN/ERROR，所以它本身不能带凭证
    org.assertj.core.api.Assertions.assertThat(e.getMessage())
        .as("官方文档形态的会话能力（?session=<32 位十六进制>）可让持有者以机器人身份发消息，不能进日志")
        .doesNotContain("66d7c695a9e1f18782a7cba1d9deb885")
        .doesNotContain("session=");
    // 但排查仍要能看出是哪个端点
    org.assertj.core.api.Assertions.assertThat(e.getMessage())
        .contains("sendBySession")
        .contains("310000");
  }

  @Test
  @DisplayName("日志安全的 URL 外形：去掉 query / fragment / userinfo，保留 scheme+host+path")
  void sanitizedUrlKeepsOnlyTheEndpoint() {
    // 通过一个必然失败的业务响应来观察异常文案里的 URL 外形
    String body = "{\"errcode\":1}";
    String withUserInfo =
        "https://user:pass@oapi.dingtalk.com/robot/sendBySession?session=abc#frag";
    String msg =
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> DingTalkMessageSender.rejectBusinessError(body, withUserInfo))
            .getMessage();

    org.assertj.core.api.Assertions.assertThat(msg)
        .contains("oapi.dingtalk.com/robot/sendBySession")
        .doesNotContain("user:pass")
        .doesNotContain("session=abc")
        .doesNotContain("frag");
  }

  @Test
  @DisplayName("群里换人提问后，前一条的回复仍 @ 原来的提问者")
  void replyTargetsItsOwnAskerNotTheLatestOne() throws Exception {
    String webhookUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    DingTalkMessageSender sender =
        new DingTalkMessageSender(target -> {}, DingTalkMessageSender.DEFAULT_CHUNK_SIZE);

    // A 提问 →（答复还在产出）→ B 也提问
    sender.rememberSession("conv-g", webhookUrl, "staff-A", "msg-A");
    sender.rememberSession("conv-g", webhookUrl, "staff-B", "msg-B");

    // 现在发的是对 A 那条的回复
    sender.send("conv-g", "回答 A", "msg-A");

    JsonNode body = MAPPER.readTree(bodies.get(0));
    assertEquals(
        "staff-A",
        body.path("at").path("atUserIds").get(0).asText(),
        "回答 A 的问题却 @ 了 B —— 群里换人提问就会串");
  }

  @Test
  @DisplayName("群聊 @ 目标的记忆有上界，超出的最早条目被淘汰")
  void atTargetMemoryIsBounded() throws Exception {
    String webhookUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    DingTalkMessageSender sender =
        new DingTalkMessageSender(target -> {}, DingTalkMessageSender.DEFAULT_CHUNK_SIZE);

    int cap = DingTalkMessageSender.MAX_TRACKED_QUESTIONS;
    for (int i = 0; i <= cap; i++) {
      sender.rememberSession("conv-g", webhookUrl, "staff-" + i, "msg-" + i);
    }

    sender.send("conv-g", "答最早的", "msg-0");
    assertTrue(
        MAPPER.readTree(bodies.get(0)).path("at").isMissingNode(),
        "被淘汰的条目应当取不到 @ 目标（宁可不 @，也不要 @ 错人）");

    sender.send("conv-g", "答最近的", "msg-" + cap);
    assertEquals(
        "staff-" + cap,
        MAPPER.readTree(bodies.get(1)).path("at").path("atUserIds").get(0).asText());
  }
}
