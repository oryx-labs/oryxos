package io.oryxos.channel.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageService;
import io.oryxos.core.channel.OutboundGuard;
import io.oryxos.core.profile.ProfileRegistry;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 适配器交给核心的附件里不得出现含 bot token 的下载地址。
 *
 * <p>这一层是必要的：只测 resolver 无法说明适配器有没有真的用它 —— 换回旧实现（直接交出 {@code getFile} 得到的地址）时，只测 resolver 的用例照样通过。
 */
class TelegramChannelAdapterMediaTest {

  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("适配器交出的是本地路径：含 token 的下载地址不得进入交给核心的消息")
  void adapterHandsOverALocalPath(@TempDir Path tempDir) throws Exception {
    byte[] payload = "telegram-bytes".getBytes(StandardCharsets.UTF_8);
    String token = "123456:AAsecrettoken";
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/file/bot" + token + "/documents/f1.txt",
        exchange -> {
          exchange.sendResponseHeaders(200, payload.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
          }
        });
    server.start();
    String apiBase = "http://127.0.0.1:" + server.getAddress().getPort();

    // 真实适配器 + 桩 sender（sender 的 resolveFileUrl 返回含 token 的地址，正是生产里的形状）
    ChannelConfig config =
        new ChannelConfig("tg", "telegram", "app-id", "app-secret", "agent", true);
    TelegramChannelAdapter adapter =
        new TelegramChannelAdapter(
            config,
            mock(ProfileRegistry.class),
            mock(InboundMessageService.class),
            mock(OutboundGuard.class),
            apiBase);
    TelegramMessageSender sender = mock(TelegramMessageSender.class);
    when(sender.resolveFileUrl("file_id_1"))
        .thenReturn(apiBase + "/file/bot" + token + "/documents/f1.txt");

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

    InboundMessage resolved = adapter.resolveMedia(message, sender);
    InboundAttachment attachment = resolved.attachments().get(0);

    assertThat(attachment.url())
        .as("适配器交给核心的必须是本机路径，不是含 token 的下载地址")
        .doesNotContain(token)
        .doesNotStartWith("http");
    assertThat(Files.readString(Path.of(attachment.url()))).isEqualTo("telegram-bytes");
    assertThat(attachment.reference()).isEqualTo("file_id_1");
  }
}
