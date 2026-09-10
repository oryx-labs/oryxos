package io.oryxos.tool.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.oryxos.tool.sandbox.PermissiveSandbox;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientResponseException;

/**
 * 三家专用渠道 Adapter（课件 6.4 路一的扩展实现）：断言各家约定的 body 格式与钉钉加签 URL。
 *
 * <p>格式兼容性的最终确认归人工冒烟（真实群机器人）——本测试钉死的是"我们发的就是各家文档约定的形态"。
 */
class VendorNotifyAdapterTest {

  private record ReceivedRequest(
      String method, String path, String query, String body, String authorization) {}

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private HttpServer server;
  private final List<ReceivedRequest> received = new ArrayList<>();
  private volatile int responseStatus = 200;
  private volatile String responseBody = "";
  private NotifyPoster poster;

  @BeforeEach
  void startFakeWebhook() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/",
        exchange -> {
          record(exchange);
        });
    server.start();
    poster = new NotifyPoster(new PermissiveSandbox());
  }

  @AfterEach
  void stopFakeWebhook() {
    server.stop(0);
  }

  private void record(HttpExchange exchange) throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    received.add(
        new ReceivedRequest(
            exchange.getRequestMethod(),
            exchange.getRequestURI().getRawPath(),
            exchange.getRequestURI().getQuery(),
            body,
            exchange.getRequestHeaders().getFirst("Authorization")));
    byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
    if (!responseBody.isEmpty()) {
      exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    }
    exchange.sendResponseHeaders(responseStatus, payload.length);
    if (payload.length > 0) {
      exchange.getResponseBody().write(payload);
    }
    exchange.close();
  }

  private String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
  }

  private JsonNode lastBody() throws IOException {
    return MAPPER.readTree(received.get(received.size() - 1).body());
  }

  @Test
  @DisplayName("企业微信：msgtype/text.content 格式")
  void wecomBodyMatchesVendorContract() throws IOException {
    new WeComNotifyAdapter(poster).send(new NotifyTarget("wecom", Map.of("url", url())), "日报来了");

    JsonNode body = lastBody();
    assertEquals("text", body.get("msgtype").asText());
    assertEquals("日报来了", body.get("text").get("content").asText());
  }

  @Test
  @DisplayName("企业微信：format=markdown 发官方 markdown 体")
  void wecomMarkdownFormatMatchesVendorContract() throws IOException {
    new WeComNotifyAdapter(poster)
        .send(
            new NotifyTarget("wecom", Map.of("url", url(), "format", "markdown")),
            "**告警** <font color=\"warning\">1</font>");

    JsonNode body = lastBody();
    assertEquals("markdown", body.get("msgtype").asText());
    assertEquals(
        "**告警** <font color=\"warning\">1</font>", body.get("markdown").get("content").asText());
  }

  @Test
  @DisplayName("企业微信：未知 format 点名拒绝且零请求")
  void wecomRejectsUnknownFormat() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WeComNotifyAdapter(poster)
                .send(
                    new NotifyTarget("wecom", Map.of("url", url(), "format", "template_card")),
                    "x"));
    assertEquals(0, received.size());
  }

  @Test
  @DisplayName("飞书/Lark：msg_type/content.text 格式")
  void feishuBodyMatchesVendorContract() throws IOException {
    new FeishuNotifyAdapter(poster).send(new NotifyTarget("feishu", Map.of("url", url())), "日报来了");

    JsonNode body = lastBody();
    assertEquals("text", body.get("msg_type").asText());
    assertEquals("日报来了", body.get("content").get("text").asText());
  }

  @Test
  @DisplayName("飞书：format=post 发官方富文本体")
  void feishuPostFormatMatchesVendorContract() throws IOException {
    new FeishuNotifyAdapter(poster)
        .send(
            new NotifyTarget("feishu", Map.of("url", url(), "format", "post", "title", "更新")),
            "项目已更新");

    JsonNode body = lastBody();
    assertEquals("post", body.get("msg_type").asText());
    assertEquals("更新", body.get("content").get("post").get("zh_cn").get("title").asText());
    assertEquals(
        "项目已更新",
        body.get("content")
            .get("post")
            .get("zh_cn")
            .get("content")
            .get(0)
            .get(0)
            .get("text")
            .asText());
  }

  @Test
  @DisplayName("飞书：format=markdown 发互动卡片 lark_md（可渲染 Markdown）")
  void feishuMarkdownAliasUsesInteractiveLarkMd() throws IOException {
    new FeishuNotifyAdapter(poster)
        .send(
            new NotifyTarget("feishu", Map.of("url", url(), "format", "markdown")),
            "## 告警\nCPU 90%");

    JsonNode body = lastBody();
    assertEquals("interactive", body.get("msg_type").asText());
    assertEquals(
        "lark_md", body.get("card").get("elements").get(0).get("text").get("tag").asText());
    assertEquals(
        "## 告警\nCPU 90%",
        body.get("card").get("elements").get(0).get("text").get("content").asText());
  }

  @Test
  @DisplayName("飞书：format=interactive 发简易卡片")
  void feishuInteractiveFormatMatchesVendorContract() throws IOException {
    new FeishuNotifyAdapter(poster)
        .send(
            new NotifyTarget(
                "feishu", Map.of("url", url(), "format", "interactive", "title", "告警")),
            "**CPU** 90%");

    JsonNode body = lastBody();
    assertEquals("interactive", body.get("msg_type").asText());
    assertEquals("告警", body.get("card").get("header").get("title").get("content").asText());
    assertEquals(
        "lark_md", body.get("card").get("elements").get(0).get("text").get("tag").asText());
    assertEquals(
        "**CPU** 90%", body.get("card").get("elements").get(0).get("text").get("content").asText());
  }

  @Test
  @DisplayName("飞书加签：config 含 secret 时 body 带 timestamp+sign")
  void feishuSignedBodyCarriesTimestampAndSign() throws IOException {
    new FeishuNotifyAdapter(poster)
        .send(new NotifyTarget("feishu", Map.of("url", url(), "secret", "test-secret")), "hi");

    JsonNode body = lastBody();
    assertTrue(body.hasNonNull("timestamp"), "加签模式必须带 timestamp");
    assertTrue(body.hasNonNull("sign"), "加签模式必须带 sign");
    assertEquals(null, received.get(0).query(), "飞书签名在 body，不拼 URL");
  }

  @Test
  @DisplayName("飞书：未知 format 点名拒绝且零请求")
  void feishuRejectsUnknownFormat() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FeishuNotifyAdapter(poster)
                .send(
                    new NotifyTarget("feishu", Map.of("url", url(), "format", "share_chat")), "x"));
    assertEquals(0, received.size());
  }

  @Test
  @DisplayName("钉钉：msgtype/text.content 格式（关键词模式，无签名参数）")
  void dingTalkBodyMatchesVendorContract() throws IOException {
    new DingTalkNotifyAdapter(poster)
        .send(new NotifyTarget("dingtalk", Map.of("url", url())), "OryxOS日报来了");

    JsonNode body = lastBody();
    assertEquals("text", body.get("msgtype").asText());
    assertEquals("OryxOS日报来了", body.get("text").get("content").asText());
    assertEquals(null, received.get(0).query(), "关键词模式不拼签名参数");
  }

  @Test
  @DisplayName("钉钉：format=markdown 发官方 markdown 体")
  void dingTalkMarkdownFormatMatchesVendorContract() throws IOException {
    new DingTalkNotifyAdapter(poster)
        .send(
            new NotifyTarget(
                "dingtalk", Map.of("url", url(), "format", "markdown", "title", "杭州天气")),
            "#### 杭州天气\n> 9度");

    JsonNode body = lastBody();
    assertEquals("markdown", body.get("msgtype").asText());
    assertEquals("杭州天气", body.get("markdown").get("title").asText());
    assertEquals("#### 杭州天气\n> 9度", body.get("markdown").get("text").asText());
  }

  @Test
  @DisplayName("钉钉：format=actionCard 发整体跳转卡片")
  void dingTalkActionCardFormatMatchesVendorContract() throws IOException {
    new DingTalkNotifyAdapter(poster)
        .send(
            new NotifyTarget(
                "dingtalk",
                Map.of(
                    "url",
                    url(),
                    "format",
                    "actionCard",
                    "title",
                    "告警",
                    "single_url",
                    "https://example.com/alert",
                    "single_title",
                    "查看")),
            "### CPU 过高");

    JsonNode body = lastBody();
    assertEquals("actionCard", body.get("msgtype").asText());
    assertEquals("告警", body.get("actionCard").get("title").asText());
    assertEquals("### CPU 过高", body.get("actionCard").get("text").asText());
    assertEquals("查看", body.get("actionCard").get("singleTitle").asText());
    assertEquals("https://example.com/alert", body.get("actionCard").get("singleURL").asText());
  }

  @Test
  @DisplayName("钉钉：actionCard 缺 single_url 点名拒绝且零请求")
  void dingTalkActionCardRequiresSingleUrl() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DingTalkNotifyAdapter(poster)
                .send(
                    new NotifyTarget("dingtalk", Map.of("url", url(), "format", "actionCard")),
                    "x"));
    assertEquals(0, received.size());
  }

  @Test
  @DisplayName("钉钉：未知 format 点名拒绝且零请求")
  void dingTalkRejectsUnknownFormat() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DingTalkNotifyAdapter(poster)
                .send(
                    new NotifyTarget("dingtalk", Map.of("url", url(), "format", "feedCard")), "x"));
    assertEquals(0, received.size());
  }

  @Test
  @DisplayName("钉钉加签：config 含 secret 时 URL 拼 timestamp+sign")
  void dingTalkSignedUrlCarriesTimestampAndSign() {
    new DingTalkNotifyAdapter(poster)
        .send(new NotifyTarget("dingtalk", Map.of("url", url(), "secret", "test-secret")), "hi");

    String query = received.get(0).query();
    assertTrue(query.contains("timestamp="), "加签模式必须带 timestamp");
    assertTrue(query.contains("sign="), "加签模式必须带 sign");
  }

  @Test
  @DisplayName("三家同口径：对端 5xx 异常上抛不吞")
  void vendorAdaptersPropagateServerError() {
    responseStatus = 500;
    NotifyTarget target = new NotifyTarget("wecom", Map.of("url", url()));

    assertThrows(
        RestClientResponseException.class, () -> new WeComNotifyAdapter(poster).send(target, "hi"));
  }

  @Test
  @DisplayName("企微：HTTP 200 + errcode≠0 业务失败上抛")
  void wecomBusinessErrorIn2xxBodyFailsLoud() {
    responseBody = "{\"errcode\":93017,\"errmsg\":\"invalid webhook url\"}";
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () ->
                new WeComNotifyAdapter(poster)
                    .send(new NotifyTarget("wecom", Map.of("url", url())), "hi"));
    assertTrue(ex.getMessage().contains("errcode=93017"));
    assertTrue(ex.getMessage().contains("invalid webhook url"));
    assertEquals(1, received.size());
  }

  @Test
  @DisplayName("飞书：HTTP 200 + code≠0 业务失败上抛")
  void feishuBusinessErrorIn2xxBodyFailsLoud() {
    responseBody = "{\"code\":19021,\"msg\":\"sign match fail\"}";
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () ->
                new FeishuNotifyAdapter(poster)
                    .send(new NotifyTarget("feishu", Map.of("url", url())), "hi"));
    assertTrue(ex.getMessage().contains("code=19021"));
    assertTrue(ex.getMessage().contains("sign match fail"));
  }

  @Test
  @DisplayName("钉钉：HTTP 200 + errcode≠0 业务失败上抛")
  void dingTalkBusinessErrorIn2xxBodyFailsLoud() {
    responseBody = "{\"errcode\":310000,\"errmsg\":\"关键词不匹配\"}";
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () ->
                new DingTalkNotifyAdapter(poster)
                    .send(new NotifyTarget("dingtalk", Map.of("url", url())), "hi"));
    assertTrue(ex.getMessage().contains("errcode=310000"));
  }

  @Test
  @DisplayName("厂商 webhook：HTTP 200 + 业务码=0 视为成功")
  void vendorBusinessCodeZeroSucceeds() {
    responseBody = "{\"errcode\":0,\"errmsg\":\"ok\"}";
    new WeComNotifyAdapter(poster).send(new NotifyTarget("wecom", Map.of("url", url())), "hi");
    assertEquals(1, received.size());
  }

  @Test
  @DisplayName("通用 webhook：无业务错误字段的 JSON 不误伤")
  void genericWebhookJsonWithoutBusinessCodeSucceeds() {
    responseBody = "{\"content\":\"accepted\"}";
    new WebhookNotifyAdapter(poster).send(new NotifyTarget("webhook", Map.of("url", url())), "hi");
    assertEquals(1, received.size());
  }

  @Test
  @DisplayName("三家同口径：缺 url 报错点名零请求")
  void vendorAdaptersFailFastOnMissingUrl() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new FeishuNotifyAdapter(poster).send(new NotifyTarget("feishu", Map.of()), "hi"));
    assertEquals(0, received.size());
  }

  @Test
  @DisplayName("Slack Incoming Webhook：text 字段")
  void slackWebhookBodyMatchesVendorContract() throws IOException {
    new SlackNotifyAdapter(poster).send(new NotifyTarget("slack", Map.of("url", url())), "日报来了");
    JsonNode body = lastBody();
    assertEquals("日报来了", body.get("text").asText());
  }

  @Test
  @DisplayName("Slack：缺 url 且缺 token/channel_id 点名拒绝")
  void slackRejectsMissingTarget() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new SlackNotifyAdapter(poster).send(new NotifyTarget("slack", Map.of()), "hi"));
    assertEquals(0, received.size());
  }

  @Test
  @DisplayName("Discord Incoming Webhook：content 字段")
  void discordWebhookBodyMatchesVendorContract() throws IOException {
    new DiscordNotifyAdapter(poster)
        .send(new NotifyTarget("discord", Map.of("url", url())), "日报来了");
    JsonNode body = lastBody();
    assertEquals("日报来了", body.get("content").asText());
  }

  @Test
  @DisplayName("Discord：缺 url 且缺 token/channel_id 点名拒绝")
  void discordRejectsMissingTarget() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new DiscordNotifyAdapter(poster).send(new NotifyTarget("discord", Map.of()), "hi"));
    assertEquals(0, received.size());
  }

  @Test
  @DisplayName("Telegram / Teams / Google Chat / Mattermost webhook 体")
  void overseasWebhookBodies() throws IOException {
    new TelegramNotifyAdapter(poster)
        .send(new NotifyTarget("telegram", Map.of("url", url(), "chat_id", "1")), "hi");
    assertEquals("hi", lastBody().get("text").asText());

    new TeamsNotifyAdapter(poster).send(new NotifyTarget("teams", Map.of("url", url())), "hi");
    assertEquals("hi", lastBody().get("text").asText());

    new GoogleChatNotifyAdapter(poster).send(new NotifyTarget("gchat", Map.of("url", url())), "hi");
    assertEquals("hi", lastBody().get("text").asText());

    new MattermostNotifyAdapter(poster)
        .send(new NotifyTarget("mattermost", Map.of("url", url())), "hi");
    assertEquals("hi", lastBody().get("text").asText());
  }

  @Test
  @DisplayName("WhatsApp Graph：messaging_product + text.body")
  void whatsappGraphBody() throws IOException {
    new WhatsAppNotifyAdapter(poster)
        .send(
            new NotifyTarget("whatsapp", Map.of("url", url(), "token", "tok", "to", "16315551181")),
            "hello");
    JsonNode body = lastBody();
    assertEquals("whatsapp", body.get("messaging_product").asText());
    assertEquals("hello", body.get("text").get("body").asText());
  }

  @Test
  @DisplayName("Matrix：缺配置点名拒绝")
  void matrixRejectsMissingTarget() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new MatrixNotifyAdapter(poster).send(new NotifyTarget("matrix", Map.of()), "hi"));
    assertEquals(0, received.size());
  }

  @Test
  @DisplayName("Matrix：homeserver + token + room_id 走 PUT m.room.message")
  void matrixHomeserverPutBody() throws IOException {
    new MatrixNotifyAdapter(poster)
        .send(
            new NotifyTarget(
                "matrix",
                Map.of(
                    "homeserver",
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "token",
                    "tok",
                    "room_id",
                    "!r:hs")),
            "日报来了");
    JsonNode body = lastBody();
    assertEquals("m.text", body.get("msgtype").asText());
    assertEquals("日报来了", body.get("body").asText());
    ReceivedRequest last = received.get(received.size() - 1);
    assertEquals("PUT", last.method());
    assertTrue(last.path().contains("%21r%3Ahs"));
    assertTrue(!last.path().contains("%2521"), "房间 ID 不得二次编码");
  }

  @Test
  @DisplayName("QQ：缺配置点名拒绝")
  void qqRejectsMissingTarget() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new QqNotifyAdapter(poster).send(new NotifyTarget("qq", Map.of()), "hi"));
    assertEquals(0, received.size());
  }

  @Test
  @DisplayName("QQ：url + token 体为 msg_type=0 + content")
  void qqNotifyBody() throws IOException {
    new QqNotifyAdapter(poster)
        .send(
            new NotifyTarget("qq", Map.of("url", url(), "token", "atok", "group_openid", "g1")),
            "告警");
    JsonNode body = lastBody();
    assertEquals(0, body.get("msg_type").asInt());
    assertEquals("告警", body.get("content").asText());
    ReceivedRequest last = received.get(received.size() - 1);
    assertEquals("QQBot atok", last.authorization());
  }
}
