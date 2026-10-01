package io.oryxos.channel.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMessage;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MatrixInboundMediaResolverTest {

  @Test
  @DisplayName("mxc 解析与鉴权媒体 URL")
  void parseAndUrls() {
    MatrixInboundMediaResolver.Mxc mxc = MatrixInboundMediaResolver.parseMxc("mxc://hs/img1");
    assertEquals("hs", mxc.server());
    assertEquals("img1", mxc.mediaId());
    String[] urls =
        MatrixInboundMediaResolver.downloadUrls("http://127.0.0.1:8008", "mxc://hs/img1");
    assertEquals("http://127.0.0.1:8008/_matrix/client/v1/media/download/hs/img1", urls[0]);
    assertEquals("http://127.0.0.1:8008/_matrix/media/v3/download/hs/img1", urls[1]);
  }

  @Test
  @DisplayName("鉴权下载落盘后 url 为本机路径")
  void downloadWritesFile(@TempDir Path dir) throws Exception {
    byte[] png =
        new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D};
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/_matrix/client/v1/media/download/hs/img1",
        exchange -> {
          exchange.sendResponseHeaders(200, png.length);
          exchange.getResponseBody().write(png);
          exchange.close();
        });
    server.start();
    try {
      String hs = "http://127.0.0.1:" + server.getAddress().getPort();
      MatrixInboundMediaResolver resolver =
          new MatrixInboundMediaResolver(url -> {}, hs, "tok", dir, "ops-matrix");
      InboundMessage incoming =
          new InboundMessage(
              "matrix",
              "ops-matrix",
              "$e1",
              ChatKind.P2P,
              "@alice:hs",
              "!dm:hs",
              "",
              false,
              false,
              List.of(
                  new InboundAttachment(
                      InboundAttachment.TYPE_IMAGE, null, "mxc://hs/img1", "a.png")));
      InboundMessage out = resolver.download(incoming);
      assertEquals(1, out.attachments().size());
      String url = out.attachments().get(0).url();
      assertTrue(url != null && Files.exists(Path.of(url)));
      assertEquals("mxc://hs/img1", out.attachments().get(0).reference());
    } finally {
      server.stop(0);
    }
  }
}
