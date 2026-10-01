package io.oryxos.channel.wecom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMessage;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
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

class WeComInboundImageResolverTest {

  @TempDir Path mediaRoot;

  private HttpServer server;
  private String baseUrl;
  private final AtomicInteger fileHits = new AtomicInteger();

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext(
        "/img",
        exchange -> {
          fileHits.incrementAndGet();
          byte[] body = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01, 0x02};
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
  @DisplayName("远程 URL 下载为本地路径，reference 保留原 URL")
  void downloadsRemoteUrlToLocalPath() throws Exception {
    String remote = baseUrl + "/img";
    WeComInboundImageResolver resolver =
        new WeComInboundImageResolver(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
            mediaRoot,
            "ops-wecom",
            true);
    InboundMessage input =
        new InboundMessage(
            "wecom",
            "ops-wecom",
            "msg-1",
            ChatKind.P2P,
            "u1",
            "chat-1",
            "",
            false,
            false,
            List.of(InboundAttachment.imageUrl(remote)));

    InboundMessage out = resolver.resolve(input);

    assertEquals(1, out.attachments().size());
    InboundAttachment att = out.attachments().get(0);
    assertEquals(remote, att.reference());
    assertNotEquals(remote, att.url());
    assertTrue(Files.isRegularFile(Path.of(att.url())), att.url());
    assertEquals(1, fileHits.get());
  }

  @Test
  @DisplayName("允许企微 COS / 微信图床主机名")
  void allowsWeComCosHosts() {
    assertTrue(
        WeComInboundImageResolver.isAllowedMediaHost(
            "ww-aibot-img-1258476243.cos.ap-guangzhou.myqcloud.com"));
    assertTrue(WeComInboundImageResolver.isAllowedMediaHost("img.weixin.qq.com"));
  }

  @Test
  @DisplayName("下载失败保留远程 URL")
  void downloadFailureKeepsRemoteUrl() {
    WeComInboundImageResolver resolver =
        new WeComInboundImageResolver(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
            mediaRoot,
            "ops-wecom");
    String remote =
        "https://ww-aibot-img-1258476243.cos.ap-guangzhou.myqcloud.com/missing-"
            + System.nanoTime();
    InboundMessage input =
        new InboundMessage(
            "wecom",
            "ops-wecom",
            "msg-2",
            ChatKind.P2P,
            "u1",
            "chat-1",
            "",
            false,
            false,
            List.of(InboundAttachment.imageUrl(remote)));

    InboundMessage out = resolver.resolve(input);

    assertEquals(remote, out.attachments().get(0).url());
  }

  @Test
  @DisplayName("视频无后缀 URL → 落盘默认 .mp4")
  void videoWithoutExtensionGetsMp4() throws Exception {
    byte[] ftyp =
        new byte[] {
          0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 1, 2, 3, 4, 5, 6, 7, 8
        };
    server.createContext(
        "/vid",
        exchange -> {
          exchange.sendResponseHeaders(200, ftyp.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(ftyp);
          }
        });
    String remote = baseUrl + "/vid";
    WeComInboundImageResolver resolver =
        new WeComInboundImageResolver(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
            mediaRoot,
            "ops-wecom",
            true);
    InboundMessage out =
        resolver.resolve(
            new InboundMessage(
                "wecom",
                "ops-wecom",
                "msg-vid",
                ChatKind.P2P,
                "u1",
                "chat-1",
                "",
                false,
                false,
                List.of(InboundAttachment.videoUrl(remote))));
    String url = out.attachments().get(0).url();
    assertTrue(url.endsWith(".mp4"), url);
    assertTrue(Files.isRegularFile(Path.of(url)), url);
  }
}
