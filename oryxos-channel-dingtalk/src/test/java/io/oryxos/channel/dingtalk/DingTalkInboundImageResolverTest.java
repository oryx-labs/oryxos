package io.oryxos.channel.dingtalk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMessage;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DingTalkInboundImageResolverTest {

  @TempDir Path mediaRoot;

  private HttpServer server;
  private String baseUrl;
  private final AtomicInteger tokenCalls = new AtomicInteger();
  private final AtomicInteger metaCalls = new AtomicInteger();

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    int port = server.getAddress().getPort();
    baseUrl = "http://127.0.0.1:" + port;
    server.createContext(
        "/v1.0/oauth2/accessToken",
        exchange -> {
          tokenCalls.incrementAndGet();
          byte[] body =
              "{\"accessToken\":\"tok-1\",\"expireIn\":7200}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.createContext(
        "/v1.0/robot/messageFiles/download",
        exchange -> {
          metaCalls.incrementAndGet();
          String downloadUrl = baseUrl + "/file.bin";
          byte[] body =
              ("{\"downloadUrl\":\"" + downloadUrl + "\"}").getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.createContext(
        "/file.bin",
        exchange -> {
          // Minimal JPEG SOI so ImageMime can sniff
          byte[] body = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01, 0x02, 0x03};
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
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
  @DisplayName("downloadCode → 临时 URL → 本地绝对路径，保留 reference")
  void downloadsDownloadCodeToLocalPath() throws Exception {
    DingTalkInboundImageResolver resolver =
        new DingTalkInboundImageResolver(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
            url -> {},
            baseUrl,
            "app-key",
            "app-secret",
            "app-key",
            mediaRoot,
            "ops-dingtalk");
    InboundMessage input =
        new InboundMessage(
            "dingtalk",
            "ops-dingtalk",
            "msg-1",
            ChatKind.P2P,
            "u1",
            "conv-1",
            "",
            false,
            false,
            List.of(InboundAttachment.imageReference("dl-code-1")));

    InboundMessage out = resolver.resolve(input);

    assertEquals(1, out.attachments().size());
    InboundAttachment att = out.attachments().get(0);
    assertEquals("dl-code-1", att.reference());
    assertTrue(att.url() != null && !att.url().isBlank());
    assertTrue(Files.isRegularFile(Path.of(att.url())), att.url());
    assertEquals(1, tokenCalls.get());
    assertEquals(1, metaCalls.get());
  }

  @Test
  @DisplayName("下载元信息失败时保留 downloadCode，不抛异常")
  void downloadFailureKeepsReference() throws Exception {
    server.removeContext("/v1.0/robot/messageFiles/download");
    server.createContext(
        "/v1.0/robot/messageFiles/download",
        exchange -> {
          byte[] body = "{\"code\":\"InvalidParameter\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(400, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    DingTalkInboundImageResolver resolver =
        new DingTalkInboundImageResolver(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
            url -> {},
            baseUrl,
            "app-key",
            "app-secret",
            "app-key",
            mediaRoot,
            "ops-dingtalk");
    InboundMessage input =
        new InboundMessage(
            "dingtalk",
            "ops-dingtalk",
            "msg-2",
            ChatKind.P2P,
            "u1",
            "conv-1",
            "",
            false,
            false,
            List.of(InboundAttachment.imageReference("dl-bad")));

    InboundMessage out = resolver.resolve(input);

    assertEquals("dl-bad", out.attachments().get(0).reference());
    assertTrue(out.attachments().get(0).url() == null || out.attachments().get(0).url().isBlank());
  }

  @Test
  @DisplayName("已有 picURL 的附件跳过下载")
  void skipsWhenUrlPresent() {
    DingTalkInboundImageResolver resolver =
        new DingTalkInboundImageResolver(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
            url -> {},
            baseUrl,
            "app-key",
            "app-secret",
            "app-key",
            mediaRoot,
            "ops-dingtalk");
    InboundMessage input =
        new InboundMessage(
            "dingtalk",
            "ops-dingtalk",
            "msg-3",
            ChatKind.P2P,
            "u1",
            "conv-1",
            "",
            false,
            false,
            List.of(InboundAttachment.imageUrl("https://example.com/a.jpg")));

    InboundMessage out = resolver.resolve(input);

    assertEquals(input, out);
    assertEquals(0, tokenCalls.get());
  }

  @Test
  @DisplayName("钉钉 OSS 临时域名（含 http）在 allowlist 内")
  void allowsDingTalkOssHosts() {
    assertTrue(
        DingTalkInboundImageResolver.isAllowedMediaHost(
            "wukong-file-im-zjk.oss-cn-zhangjiakou.aliyuncs.com"));
    assertTrue(DingTalkInboundImageResolver.isAllowedMediaHost("cdn.dingtalk.com"));
    assertTrue(DingTalkInboundImageResolver.isAllowedMediaHost("img.alicdn.com"));
  }
}
