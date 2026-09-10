package io.oryxos.channel.weixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMessage;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WeixinEventNormalizerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final WeixinEventNormalizer normalizer = new WeixinEventNormalizer("ops-weixin", "bot-1");

  @Test
  @DisplayName("私聊文本 → InboundMessage")
  void p2pText() {
    Optional<InboundMessage> msg = normalizer.normalize(sample("u1", "你好", null));
    assertTrue(msg.isPresent());
    assertEquals("你好", msg.get().content());
    assertEquals("user:u1", msg.get().chatId());
    assertTrue(msg.get().textual());
  }

  @Test
  @DisplayName("群消息忽略")
  void skipGroup() {
    ObjectNode root = sample("u1", "hi", null);
    root.put("room_id", "r1");
    assertTrue(normalizer.normalize(root).isEmpty());
  }

  @Test
  @DisplayName("自身消息忽略")
  void skipSelf() {
    assertTrue(normalizer.normalize(sample("bot-1", "x", null)).isEmpty());
  }

  @Test
  @DisplayName("无文本无媒体 → 忽略")
  void skipBareNonText() {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("from_user_id", "u1");
    root.put("message_id", "m1");
    ArrayNode items = root.putArray("item_list");
    ObjectNode item = items.addObject();
    item.put("type", 2);
    assertTrue(normalizer.normalize(root).isEmpty());
  }

  @Test
  @DisplayName("图片 CDN 引用 → attachment")
  void imageAttachment() {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("from_user_id", "u1");
    root.put("message_id", "m-img");
    ArrayNode items = root.putArray("item_list");
    ObjectNode item = items.addObject();
    item.put("type", 2);
    ObjectNode media = item.putObject("image_item").putObject("media");
    media.put("encrypt_query_param", "enc-1");
    media.put("aes_key", "YWE=");
    Optional<InboundMessage> msg = normalizer.normalize(root);
    assertTrue(msg.isPresent());
    assertFalse(msg.get().textual());
    assertEquals(1, msg.get().attachments().size());
    InboundAttachment a = msg.get().attachments().get(0);
    assertEquals(InboundAttachment.TYPE_IMAGE, a.type());
    assertTrue(a.url().contains("novac2c.cdn.weixin.qq.com"));
    assertTrue(a.url().contains("encrypted_query_param="));
  }

  @Test
  @DisplayName("语音 ASR 文本进正文")
  void voiceAsrText() {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("from_user_id", "u1");
    root.put("message_id", "m-voice");
    ArrayNode items = root.putArray("item_list");
    ObjectNode item = items.addObject();
    item.put("type", 3);
    ObjectNode voice = item.putObject("voice_item");
    voice.put("text", "你好世界");
    ObjectNode media = voice.putObject("media");
    media.put("encrypt_query_param", "enc-v");
    media.put("aes_key", "YWE=");
    Optional<InboundMessage> msg = normalizer.normalize(root);
    assertTrue(msg.isPresent());
    assertEquals("你好世界", msg.get().content());
    assertEquals(InboundAttachment.TYPE_AUDIO, msg.get().attachments().get(0).type());
  }

  @Test
  @DisplayName("PDF 文件名保留")
  void filePdf() {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("from_user_id", "u1");
    root.put("message_id", "m-pdf");
    ArrayNode items = root.putArray("item_list");
    ObjectNode item = items.addObject();
    item.put("type", 4);
    ObjectNode file = item.putObject("file_item");
    file.put("file_name", "spec.pdf");
    ObjectNode media = file.putObject("media");
    media.put("full_url", "https://novac2c.cdn.weixin.qq.com/c2c/x.pdf");
    media.put("aes_key", "YWE=");
    Optional<InboundMessage> msg = normalizer.normalize(root);
    assertTrue(msg.isPresent());
    assertEquals("spec.pdf", msg.get().attachments().get(0).fileName());
    assertEquals(InboundAttachment.TYPE_FILE, msg.get().attachments().get(0).type());
  }

  private static ObjectNode sample(String from, String text, String room) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("from_user_id", from);
    root.put("message_id", "m-" + from);
    root.put("context_token", "ctx-" + from);
    if (room != null) {
      root.put("room_id", room);
    }
    ArrayNode items = root.putArray("item_list");
    ObjectNode item = items.addObject();
    item.put("type", 1);
    item.putObject("text_item").put("text", text);
    return root;
  }
}
