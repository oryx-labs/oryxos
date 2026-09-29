package io.oryxos.channel.slack;

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

class SlackEventNormalizerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private SlackEventNormalizer normalizer;

  @BeforeEach
  void setUp() {
    normalizer = new SlackEventNormalizer("ops-slack");
  }

  @Test
  @DisplayName("私聊 message + channel_type=im → P2P 文本")
  void dmMessage() {
    ObjectNode event = MAPPER.createObjectNode();
    event.put("type", "message");
    event.put("channel_type", "im");
    event.put("user", "U111");
    event.put("channel", "D222");
    event.put("ts", "1710000000.000100");
    event.put("text", "hello slack");
    Optional<InboundMessage> msg = normalizer.normalize(event);
    assertTrue(msg.isPresent());
    InboundMessage m = msg.get();
    assertEquals(ChatKind.P2P, m.chatKind());
    assertEquals("hello slack", m.content());
    assertEquals("D222", m.chatId());
    assertEquals("U111", m.userId());
    assertTrue(m.textual());
  }

  @Test
  @DisplayName("app_mention → GROUP 且剥离 <@BOT>")
  void appMention() {
    ObjectNode event = MAPPER.createObjectNode();
    event.put("type", "app_mention");
    event.put("user", "U111");
    event.put("channel", "C333");
    event.put("ts", "1710000000.000200");
    event.put("text", "<@B0BOT> 帮我查一下天气");
    Optional<InboundMessage> msg = normalizer.normalize(event);
    assertTrue(msg.isPresent());
    InboundMessage m = msg.get();
    assertEquals(ChatKind.GROUP, m.chatKind());
    assertTrue(m.mentionedBot());
    assertEquals("帮我查一下天气", m.content());
  }

  @Test
  @DisplayName("频道普通 message（非 app_mention）丢弃")
  void channelMessageWithoutMentionDropped() {
    ObjectNode event = MAPPER.createObjectNode();
    event.put("type", "message");
    event.put("channel_type", "channel");
    event.put("user", "U111");
    event.put("channel", "C333");
    event.put("ts", "1710000000.000300");
    event.put("text", "noise");
    assertTrue(normalizer.normalize(event).isEmpty());
  }

  @Test
  @DisplayName("bot_id / 编辑 subtype 丢弃；file_share 保留")
  void botAndSubtypeHandling() {
    ObjectNode bot = MAPPER.createObjectNode();
    bot.put("type", "message");
    bot.put("channel_type", "im");
    bot.put("bot_id", "B999");
    bot.put("user", "U111");
    bot.put("channel", "D222");
    bot.put("ts", "1.1");
    bot.put("text", "from bot");
    assertTrue(normalizer.normalize(bot).isEmpty());

    ObjectNode edited = MAPPER.createObjectNode();
    edited.put("type", "message");
    edited.put("channel_type", "im");
    edited.put("subtype", "message_changed");
    edited.put("user", "U111");
    edited.put("channel", "D222");
    edited.put("ts", "1.2");
    edited.put("text", "edited");
    assertTrue(normalizer.normalize(edited).isEmpty());
  }

  @Test
  @DisplayName("file_share 私聊：解析图片附件")
  void fileShareDm() {
    ObjectNode event = MAPPER.createObjectNode();
    event.put("type", "message");
    event.put("subtype", "file_share");
    event.put("channel_type", "im");
    event.put("user", "U111");
    event.put("channel", "D222");
    event.put("ts", "1710000000.000400");
    event.put("text", "");
    var files = event.putArray("files");
    var file = files.addObject();
    file.put("name", "shot.png");
    file.put("mimetype", "image/png");
    file.put("url_private_download", "https://files.slack.com/files-pri/T-F/download/shot.png");
    Optional<InboundMessage> msg = normalizer.normalize(event);
    assertTrue(msg.isPresent());
    InboundMessage m = msg.get();
    assertEquals(1, m.attachments().size());
    assertEquals(InboundAttachment.TYPE_IMAGE, m.attachments().get(0).type());
    assertEquals("shot.png", m.attachments().get(0).fileName());
    assertFalse(m.textual());
  }
}
