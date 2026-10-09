package io.oryxos.channel.whatsapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundMessage;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WhatsAppEventNormalizerTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final WhatsAppEventNormalizer normalizer = new WhatsAppEventNormalizer("ops-wa");

  @Test
  @DisplayName("文本消息 → P2P 业务会话")
  void textMessage() {
    List<InboundMessage> msgs = normalizer.normalize(payload("text", "hello", null));
    assertEquals(1, msgs.size());
    assertEquals(ChatKind.P2P, msgs.get(0).chatKind());
    assertEquals("hello", msgs.get(0).content());
    assertEquals("16315551181", msgs.get(0).chatId());
  }

  @Test
  @DisplayName("图片 → 附件 reference")
  void imageMessage() {
    List<InboundMessage> msgs = normalizer.normalize(payload("image", null, "media-1"));
    assertEquals(1, msgs.size());
    assertEquals("media-1", msgs.get(0).attachments().get(0).reference());
  }

  @Test
  @DisplayName("空 entry 不产出")
  void emptyIgnored() throws Exception {
    assertTrue(
        normalizer
            .normalize(mapper.readTree("{\"object\":\"whatsapp_business_account\"}"))
            .isEmpty());
  }

  private ObjectNode payload(String type, String text, String mediaId) {
    return payload(type, text, mediaId, null);
  }

  private ObjectNode payload(String type, String text, String mediaId, String caption) {
    ObjectNode root = mapper.createObjectNode();
    ObjectNode value =
        root.putArray("entry").addObject().putArray("changes").addObject().putObject("value");
    ObjectNode message = value.putArray("messages").addObject();
    message.put("from", "16315551181");
    message.put("id", "wamid.1");
    message.put("timestamp", "1700000000");
    message.put("type", type);
    if (text != null) {
      message.putObject("text").put("body", text);
    }
    if (mediaId != null) {
      ObjectNode media = message.putObject(type).put("id", mediaId);
      if (caption != null) {
        media.put("caption", caption);
      }
    }
    return root;
  }

  @Test
  @DisplayName("图片带 caption → caption 进正文，且 textual 为真")
  void imageCaptionBecomesContent() {
    List<InboundMessage> msgs =
        normalizer.normalize(payload("image", null, "media-1", "这张图里有什么问题？"));
    assertEquals(1, msgs.size());
    InboundMessage m = msgs.get(0);
    assertEquals("这张图里有什么问题？", m.content());
    assertTrue(m.textual(), "带 caption 的图片应算文本");
    assertEquals("media-1", m.attachments().get(0).reference());
  }

  @Test
  @DisplayName("四个媒体类型都读 caption")
  void everyMediaTypeReadsCaption() {
    for (String type : List.of("image", "audio", "video", "document")) {
      List<InboundMessage> msgs = normalizer.normalize(payload(type, null, "media-1", "看下这个"));
      assertEquals(1, msgs.size(), type);
      assertEquals("看下这个", msgs.get(0).content(), type);
      assertTrue(msgs.get(0).textual(), type);
    }
  }

  @Test
  @DisplayName("无 caption 的媒体 → 正文为空、textual 为假（行为不变）")
  void mediaWithoutCaptionKeepsEmptyContent() {
    List<InboundMessage> msgs = normalizer.normalize(payload("image", null, "media-1"));
    assertEquals(1, msgs.size());
    assertEquals("", msgs.get(0).content());
    assertFalse(msgs.get(0).textual());
  }

  @Test
  @DisplayName("空白 caption 视同没有正文")
  void blankCaptionIsNotText() {
    List<InboundMessage> msgs = normalizer.normalize(payload("image", null, "media-1", "   "));
    assertEquals(1, msgs.size());
    assertEquals("", msgs.get(0).content());
    assertFalse(msgs.get(0).textual());
  }
}
