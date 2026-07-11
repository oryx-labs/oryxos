package io.oryxos.tool.notify;

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
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 三家专用渠道 Adapter（课件 6.4 路一的扩展实现）：断言各家约定的 body 格式与钉钉加签 URL。
 *
 * <p>格式兼容性的最终确认归人工冒烟（真实群机器人）——本测试钉死的是"我们发的就是各家文档约定的形态"。
 */
class VendorNotifyAdapterTest {

  private record ReceivedRequest(String query, String body) {}

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private HttpServer server;
  private final List<ReceivedRequest> received = new ArrayList<>();
  private volatile int responseStatus = 200;

  @BeforeEach
  void startFakeWebhook() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/",
        exchange -> {
          record(exchange);
        });
    server.start();
  }

  @AfterEach
  void stopFakeWebhook() {
    server.stop(0);
  }

  private void record(HttpExchange exchange) throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    received.add(new ReceivedRequest(exchange.getRequestURI().getQuery(), body));
    exchange.sendResponseHeaders(responseStatus, -1);
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
    new WeComNotifyAdapter(RestClient.create())
        .send(new NotifyTarget("wecom", Map.of("url", url())), "日报来了");

    JsonNode body = lastBody();
    assertEquals("text", body.get("msgtype").asText());
    assertEquals("日报来了", body.get("text").get("content").asText());
  }

  @Test
  @DisplayName("飞书/Lark：msg_type/content.text 格式")
  void feishuBodyMatchesVendorContract() throws IOException {
    new FeishuNotifyAdapter(RestClient.create())
        .send(new NotifyTarget("feishu", Map.of("url", url())), "日报来了");

    JsonNode body = lastBody();
    assertEquals("text", body.get("msg_type").asText());
    assertEquals("日报来了", body.get("content").get("text").asText());
  }

  @Test
  @DisplayName("钉钉：msgtype/text.content 格式（关键词模式，无签名参数）")
  void dingTalkBodyMatchesVendorContract() throws IOException {
    new DingTalkNotifyAdapter(RestClient.create())
        .send(new NotifyTarget("dingtalk", Map.of("url", url())), "OryxOS日报来了");

    JsonNode body = lastBody();
    assertEquals("text", body.get("msgtype").asText());
    assertEquals("OryxOS日报来了", body.get("text").get("content").asText());
    assertEquals(null, received.get(0).query(), "关键词模式不拼签名参数");
  }

  @Test
  @DisplayName("钉钉加签：config 含 secret 时 URL 拼 timestamp+sign")
  void dingTalkSignedUrlCarriesTimestampAndSign() {
    new DingTalkNotifyAdapter(RestClient.create())
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
        RestClientResponseException.class,
        () -> new WeComNotifyAdapter(RestClient.create()).send(target, "hi"));
  }

  @Test
  @DisplayName("三家同口径：缺 url 报错点名零请求")
  void vendorAdaptersFailFastOnMissingUrl() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FeishuNotifyAdapter(RestClient.create())
                .send(new NotifyTarget("feishu", Map.of()), "hi"));
    assertEquals(0, received.size());
  }
}
