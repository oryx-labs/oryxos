package io.oryxos.channel.weixinkf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMessage;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WeixinKfInboundMediaResolverTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("media_id 下载后写入本地 url")
  void downloadsToLocalUrl() {
    WeixinKfClient client =
        client(
            mediaId -> new WeixinKfMediaBlob("%PDF-1.4".getBytes(), "application/pdf", "doc.pdf"));
    WeixinKfInboundMediaResolver resolver =
        new WeixinKfInboundMediaResolver(client, tempDir, "ops-kf");
    InboundMessage in = fileMessage("mid-1", InboundAttachment.fileReference("MEDIA1", "doc.pdf"));
    InboundMessage out = resolver.resolve(in);
    assertEquals(1, out.attachments().size());
    String url = out.attachments().get(0).url();
    assertTrue(url != null && !url.isBlank());
    assertTrue(Files.exists(Path.of(url)));
    assertEquals("MEDIA1", out.attachments().get(0).reference());
  }

  @Test
  @DisplayName("下载失败保留原 reference、不写 url")
  void downloadFailureKeepsReference() {
    WeixinKfClient client =
        client(
            mediaId -> {
              throw new IllegalStateException("微信客服 /cgi-bin/media/get errcode=40007");
            });
    WeixinKfInboundMediaResolver resolver =
        new WeixinKfInboundMediaResolver(client, tempDir, "ops-kf");
    InboundMessage in =
        fileMessage("mid-fail", InboundAttachment.fileReference("BAD_MEDIA", "x.pdf"));
    InboundMessage out = resolver.resolve(in);
    assertEquals(1, out.attachments().size());
    assertEquals("BAD_MEDIA", out.attachments().get(0).reference());
    assertNull(out.attachments().get(0).url());
  }

  @Test
  @DisplayName("超时可重试一次后成功")
  void timeoutThenRetrySucceeds() {
    AtomicInteger attempts = new AtomicInteger();
    WeixinKfClient client =
        client(
            mediaId -> {
              if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException(
                    "微信客服 /cgi-bin/media/get 失败", new HttpTimeoutException("timed out"));
              }
              return new WeixinKfMediaBlob("%PDF-1.4".getBytes(), "application/pdf", "ok.pdf");
            });
    WeixinKfInboundMediaResolver resolver =
        new WeixinKfInboundMediaResolver(client, tempDir, "ops-kf");
    InboundMessage out =
        resolver.resolve(
            fileMessage("mid-retry", InboundAttachment.fileReference("MEDIA_R", "ok.pdf")));
    assertEquals(2, attempts.get());
    assertTrue(out.attachments().get(0).url() != null && !out.attachments().get(0).url().isBlank());
  }

  private static InboundMessage fileMessage(String messageId, InboundAttachment attachment) {
    return new InboundMessage(
        "weixin_kf",
        "ops-kf",
        messageId,
        ChatKind.P2P,
        "wu1",
        "kf:wk1:user:wu1",
        "",
        false,
        false,
        List.of(attachment));
  }

  @FunctionalInterface
  private interface DownloadFn {
    WeixinKfMediaBlob download(String mediaId);
  }

  private static WeixinKfClient client(DownloadFn download) {
    return new WeixinKfClient() {
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
        return download.download(mediaId);
      }
    };
  }
}
