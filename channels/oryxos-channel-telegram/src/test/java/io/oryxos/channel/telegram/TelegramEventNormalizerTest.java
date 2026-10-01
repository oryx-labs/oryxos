package io.oryxos.channel.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMessage;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TelegramEventNormalizerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private TelegramEventNormalizer normalizer;

  @BeforeEach
  void setUp() {
    normalizer = new TelegramEventNormalizer("ops-tg", "OryxBot");
  }

  @Test
  @DisplayName("私聊文本 → P2P")
  void privateText() {
    Optional<InboundMessage> msg = normalizer.normalize(update("private", "hello", false));
    assertTrue(msg.isPresent());
    InboundMessage m = msg.get();
    assertEquals(ChatKind.P2P, m.chatKind());
    assertEquals("hello", m.content());
    assertEquals("123", m.userId());
    assertFalse(m.mentionedBot());
  }

  @Test
  @DisplayName("群聊未 @Bot → 丢弃")
  void groupWithoutMentionDropped() {
    assertTrue(normalizer.normalize(update("supergroup", "hello", false)).isEmpty());
  }

  @Test
  @DisplayName("群聊 @Bot → GROUP 并剥离提及")
  void groupMention() {
    Optional<InboundMessage> msg = normalizer.normalize(update("supergroup", "@OryxBot 查天气", true));
    assertTrue(msg.isPresent());
    InboundMessage m = msg.get();
    assertEquals(ChatKind.GROUP, m.chatKind());
    assertTrue(m.mentionedBot());
    assertEquals("查天气", m.content());
  }

  @Test
  @DisplayName("私聊图片 → 附件 file_id")
  void privatePhoto() {
    ObjectNode update = update("private", "", false);
    ObjectNode message = (ObjectNode) update.get("message");
    message.putArray("photo").addObject().put("file_id", "AgAC123").put("width", 800);
    Optional<InboundMessage> msg = normalizer.normalize(update);
    assertTrue(msg.isPresent());
    assertEquals(1, msg.get().attachments().size());
    assertEquals(InboundAttachment.TYPE_IMAGE, msg.get().attachments().get(0).type());
    assertEquals("AgAC123", msg.get().attachments().get(0).reference());
  }

  @Test
  @DisplayName("私聊 sticker 无附件 → 非文本")
  void stickerNonTextual() {
    ObjectNode update = update("private", "", false);
    ((ObjectNode) update.get("message")).putObject("sticker").put("file_id", "st1");
    Optional<InboundMessage> msg = normalizer.normalize(update);
    assertTrue(msg.isPresent());
    assertFalse(msg.get().textual());
    assertTrue(msg.get().attachments().isEmpty());
  }

  private static ObjectNode update(String chatType, String text, boolean mentionEntity) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("update_id", 10);
    ObjectNode message = root.putObject("message");
    message.put("message_id", 99);
    message.putObject("from").put("id", 123).put("is_bot", false);
    message.putObject("chat").put("id", 123).put("type", chatType);
    if (!text.isEmpty()) {
      message.put("text", text);
    }
    if (mentionEntity) {
      message
          .putArray("entities")
          .addObject()
          .put("type", "mention")
          .put("offset", 0)
          .put("length", 8);
    }
    return root;
  }
}
