package io.oryxos.channel.weixinkf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WeixinKfInboundMediaResolverTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("media_id 下载后写入本地 url")
  void downloadsToLocalUrl() {
    WeixinKfClient client =
        new WeixinKfClient() {
          @Override
          public WeixinKfSyncResult syncMsg(String openKfid, String callbackToken, String cursor) {
            throw new UnsupportedOperationException();
          }

          @Override
          public void sendText(String openKfid, String externalUserId, String text) {}

          @Override
          public void ensureAiReception(String openKfid, String externalUserId) {}

          @Override
          public WeixinKfMediaBlob downloadMedia(String mediaId) {
            return new WeixinKfMediaBlob("%PDF-1.4".getBytes(), "application/pdf", "doc.pdf");
          }
        };
    WeixinKfInboundMediaResolver resolver =
        new WeixinKfInboundMediaResolver(client, tempDir, "ops-kf");
    InboundMessage in =
        new InboundMessage(
            "weixin_kf",
            "ops-kf",
            "mid-1",
            ChatKind.P2P,
            "wu1",
            "kf:wk1:user:wu1",
            "",
            false,
            false,
            List.of(InboundAttachment.fileReference("MEDIA1", "doc.pdf")));
    InboundMessage out = resolver.resolve(in);
    assertEquals(1, out.attachments().size());
    String url = out.attachments().get(0).url();
    assertTrue(url != null && !url.isBlank());
    assertTrue(Files.exists(Path.of(url)));
    assertEquals("MEDIA1", out.attachments().get(0).reference());
  }
}
