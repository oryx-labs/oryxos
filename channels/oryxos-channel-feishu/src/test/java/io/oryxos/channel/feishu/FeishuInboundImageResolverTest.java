package io.oryxos.channel.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.GetMessageResourceResp;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMessage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FeishuInboundImageResolverTest {

  @TempDir Path mediaRoot;

  @Test
  @DisplayName("image_key 下载成功 → attachment.url 为本地绝对路径，保留 reference")
  void downloadsImageKeyToLocalPath() throws Exception {
    Client client = mock(Client.class, RETURNS_DEEP_STUBS);
    GetMessageResourceResp resp = new GetMessageResourceResp();
    resp.setCode(0);
    resp.setMsg("ok");
    resp.setFileName("shot.png");
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    data.write("PNGDATA".getBytes(StandardCharsets.UTF_8));
    resp.setData(data);
    when(client.im().messageResource().get(any())).thenReturn(resp);

    FeishuInboundImageResolver resolver =
        new FeishuInboundImageResolver(client, mediaRoot, "ops-feishu");
    InboundMessage input =
        new InboundMessage(
            "feishu",
            "ops-feishu",
            "om_msg_1",
            ChatKind.P2P,
            "ou_user",
            "oc_chat",
            "",
            false,
            false,
            List.of(InboundAttachment.imageReference("img_v3_abc")));

    InboundMessage out = resolver.resolve(input);

    assertEquals(1, out.attachments().size());
    InboundAttachment att = out.attachments().get(0);
    assertEquals("img_v3_abc", att.reference());
    assertTrue(att.url() != null && !att.url().isBlank());
    assertTrue(Files.isRegularFile(Path.of(att.url())), att.url());
    assertEquals("PNGDATA", Files.readString(Path.of(att.url())));
    assertTrue(att.url().endsWith(".png") || att.url().endsWith(".PNG"), att.url());
  }

  @Test
  @DisplayName("下载失败时保留原 image_key，不抛异常")
  void downloadFailureKeepsReference() throws Exception {
    Client client = mock(Client.class, RETURNS_DEEP_STUBS);
    GetMessageResourceResp resp = new GetMessageResourceResp();
    resp.setCode(234003);
    resp.setMsg("File not in message");
    when(client.im().messageResource().get(any())).thenReturn(resp);

    FeishuInboundImageResolver resolver =
        new FeishuInboundImageResolver(client, mediaRoot, "ops-feishu");
    InboundMessage input =
        new InboundMessage(
            "feishu",
            "ops-feishu",
            "om_msg_2",
            ChatKind.P2P,
            "ou_user",
            "oc_chat",
            "",
            false,
            false,
            List.of(InboundAttachment.imageReference("img_missing")));

    InboundMessage out = resolver.resolve(input);
    assertEquals("img_missing", out.attachments().get(0).reference());
    assertTrue(out.attachments().get(0).url() == null || out.attachments().get(0).url().isBlank());
  }

  @Test
  @DisplayName("路径段消毒：去掉路径分隔与非法字符")
  void safeSegmentStripsPathTraversal() {
    assertNotEquals("../evil", FeishuInboundImageResolver.safeSegment("../evil"));
    assertTrue(FeishuInboundImageResolver.safeSegment("img/../x").contains("_"));
    assertEquals("x", FeishuInboundImageResolver.safeSegment("???"));
  }

  @Test
  @DisplayName("无后缀但魔数为 PNG → 落盘为 .png")
  void sniffsPngMagicWhenNoFilename() throws Exception {
    Client client = mock(Client.class, RETURNS_DEEP_STUBS);
    GetMessageResourceResp resp = new GetMessageResourceResp();
    resp.setCode(0);
    resp.setMsg("ok");
    resp.setFileName(null);
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    data.write(
        new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02, 0x03, 0x04});
    resp.setData(data);
    when(client.im().messageResource().get(any())).thenReturn(resp);

    FeishuInboundImageResolver resolver =
        new FeishuInboundImageResolver(client, mediaRoot, "ops-feishu");
    InboundMessage out =
        resolver.resolve(
            new InboundMessage(
                "feishu",
                "ops-feishu",
                "om_msg_3",
                ChatKind.P2P,
                "ou_user",
                "oc_chat",
                "",
                false,
                false,
                List.of(InboundAttachment.imageReference("img_magic"))));

    String url = out.attachments().get(0).url();
    assertTrue(url.endsWith(".png"), url);
  }

  @Test
  @DisplayName("media 视频：优先用 attachment.fileName 落盘扩展名")
  void videoUsesAttachmentFileNameExtension() throws Exception {
    Client client = mock(Client.class, RETURNS_DEEP_STUBS);
    GetMessageResourceResp resp = new GetMessageResourceResp();
    resp.setCode(0);
    resp.setMsg("ok");
    resp.setFileName(null);
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    data.write(new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p', 'm', 'p', '4', '1'});
    resp.setData(data);
    when(client.im().messageResource().get(any())).thenReturn(resp);

    FeishuInboundImageResolver resolver =
        new FeishuInboundImageResolver(client, mediaRoot, "ops-feishu");
    InboundMessage out =
        resolver.resolve(
            new InboundMessage(
                "feishu",
                "ops-feishu",
                "om_vid",
                ChatKind.P2P,
                "ou_user",
                "oc_chat",
                "",
                false,
                false,
                List.of(InboundAttachment.videoReference("file_vid_1", "clip.mp4"))));

    String url = out.attachments().get(0).url();
    assertTrue(url.endsWith(".mp4"), url);
  }
}
