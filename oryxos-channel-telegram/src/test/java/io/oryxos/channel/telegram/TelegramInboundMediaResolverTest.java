package io.oryxos.channel.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMessage;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Telegram 入站媒体落盘：交给核心的必须是本机路径 —— 下载地址里带着 bot token，绝不能进 prompt。 */
class TelegramInboundMediaResolverTest {

  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("附件落盘：url 变本地路径，token 不出现在任何字段里")
  void downloadsAndNeverExposesTheTokenUrl(@TempDir Path tempDir) throws Exception {
    byte[] payload = "hello-from-telegram".getBytes(StandardCharsets.UTF_8);
    String token = "123456:AAsecrettoken";
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    // Telegram 的文件下载路径形如 /file/bot<token>/<path>
    server.createContext(
        "/file/bot" + token + "/documents/file_1.txt",
        exchange -> {
          exchange.sendResponseHeaders(200, payload.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
          }
        });
    server.start();
    String apiBase = "http://127.0.0.1:" + server.getAddress().getPort();

    Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
    TelegramInboundMediaResolver resolver =
        new TelegramInboundMediaResolver(apiBase, mediaRoot, "tg");

    InboundMessage message =
        new InboundMessage(
            "telegram",
            "tg",
            "m1",
            ChatKind.P2P,
            "u1",
            "c1",
            "",
            false,
            false,
            List.of(
                new InboundAttachment(
                    InboundAttachment.TYPE_FILE, null, "file_id_1", "notes.txt")));

    InboundMessage resolved =
        resolver.resolve(
            message, fileId -> apiBase + "/file/bot" + token + "/documents/file_1.txt");

    InboundAttachment attachment = resolved.attachments().get(0);
    assertThat(attachment.url())
        .as("落盘成功后 url 必须是本机路径")
        .startsWith(mediaRoot.toAbsolutePath().toString());
    assertThat(Files.readString(Path.of(attachment.url()))).isEqualTo("hello-from-telegram");
    assertThat(attachment.reference()).as("reference 保持原始 file_id，便于排障").isEqualTo("file_id_1");

    // ★ 核心收到的整条消息里不得出现 token
    String flattened =
        attachment.url() + "|" + attachment.reference() + "|" + attachment.fileName();
    assertThat(flattened).doesNotContain(token);
  }

  @Test
  @DisplayName("下载失败：保留 file_id 降级，绝不把含 token 的地址交出去")
  void downloadFailureKeepsTheFileId(@TempDir Path tempDir) throws Exception {
    String token = "123456:AAsecrettoken";
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> exchange.sendResponseHeaders(500, -1));
    server.start();
    String apiBase = "http://127.0.0.1:" + server.getAddress().getPort();

    Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
    TelegramInboundMediaResolver resolver =
        new TelegramInboundMediaResolver(apiBase, mediaRoot, "tg");
    InboundMessage message =
        new InboundMessage(
            "telegram",
            "tg",
            "m2",
            ChatKind.P2P,
            "u1",
            "c1",
            "",
            false,
            false,
            List.of(
                new InboundAttachment(
                    InboundAttachment.TYPE_FILE, null, "file_id_2", "notes.txt")));

    InboundMessage resolved =
        resolver.resolve(
            message, fileId -> apiBase + "/file/bot" + token + "/documents/file_2.txt");

    InboundAttachment attachment = resolved.attachments().get(0);
    assertThat(attachment.url()).as("失败时不该伪造本地路径").isNullOrEmpty();
    assertThat(attachment.reference()).isEqualTo("file_id_2");
  }

  @Test
  @DisplayName("只允许配置的 API 主机：别的主机一律拒绝")
  void rejectsOtherHosts(@TempDir Path tempDir) throws Exception {
    TelegramInboundMediaResolver resolver =
        new TelegramInboundMediaResolver(
            "https://api.telegram.org", Files.createDirectories(tempDir.resolve("m")), "tg");
    assertThat(resolver.isAllowedDownloadUri(URI.create("https://api.telegram.org/file/botX/a")))
        .isTrue();
    assertThat(resolver.isAllowedDownloadUri(URI.create("https://evil.example/file/botX/a")))
        .isFalse();
    assertThat(resolver.isAllowedDownloadUri(URI.create("http://api.telegram.org/file/botX/a")))
        .as("scheme 也要一致")
        .isFalse();
  }
}
