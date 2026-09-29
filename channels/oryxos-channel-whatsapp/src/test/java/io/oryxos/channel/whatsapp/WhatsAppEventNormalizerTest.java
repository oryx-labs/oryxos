package io.oryxos.channel.whatsapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
      message.putObject(type).put("id", mediaId);
    }
    return root;
  }
}
