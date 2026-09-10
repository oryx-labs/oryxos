package io.oryxos.channel.qq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundMessage;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QqEventNormalizerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private QqEventNormalizer normalizer;

  @BeforeEach
  void setUp() {
    normalizer = new QqEventNormalizer("ops-qq");
  }

  @Test
  @DisplayName("群 @Bot 文本 → GROUP，剥离 mention")
  void groupAtMessage() {
    ObjectNode data = MAPPER.createObjectNode();
    data.put("id", "msg-g1");
    data.put("group_openid", "g-open");
    data.put("content", "<@!12345> 查天气");
    data.putObject("author").put("member_openid", "m-open");
    Optional<InboundMessage> msg = normalizer.normalize(QqEventNormalizer.EVENT_GROUP_AT, data);
    assertTrue(msg.isPresent());
    InboundMessage m = msg.get();
    assertEquals(ChatKind.GROUP, m.chatKind());
    assertTrue(m.mentionedBot());
    assertEquals("查天气", m.content());
    assertEquals("group:g-open", m.chatId());
    assertEquals("m-open", m.userId());
  }

  @Test
  @DisplayName("单聊文本 → P2P")
  void c2cText() {
    ObjectNode data = MAPPER.createObjectNode();
    data.put("id", "msg-c1");
    data.put("content", "hello");
    data.putObject("author").put("user_openid", "u-open");
    Optional<InboundMessage> msg = normalizer.normalize(QqEventNormalizer.EVENT_C2C, data);
    assertTrue(msg.isPresent());
    InboundMessage m = msg.get();
    assertEquals(ChatKind.P2P, m.chatKind());
    assertFalse(m.mentionedBot());
    assertEquals("hello", m.content());
    assertEquals("user:u-open", m.chatId());
  }

  @Test
  @DisplayName("单聊空内容 → 非文本占位")
  void c2cNonTextual() {
    ObjectNode data = MAPPER.createObjectNode();
    data.put("id", "msg-c2");
    data.put("content", "");
    data.putObject("author").put("user_openid", "u-open");
    Optional<InboundMessage> msg = normalizer.normalize(QqEventNormalizer.EVENT_C2C, data);
    assertTrue(msg.isPresent());
    assertFalse(msg.get().textual());
  }

  @Test
  @DisplayName("频道等非 MVP 事件 → 丢弃")
  void guildEventDropped() {
    ObjectNode data = MAPPER.createObjectNode();
    data.put("id", "msg-x");
    data.put("content", "hi");
    assertTrue(normalizer.normalize("AT_MESSAGE_CREATE", data).isEmpty());
  }

  @Test
  @DisplayName("群空内容 → 丢弃")
  void groupBlankDropped() {
    ObjectNode data = MAPPER.createObjectNode();
    data.put("id", "msg-g2");
    data.put("group_openid", "g-open");
    data.put("content", "<@!1>   ");
    data.putObject("author").put("member_openid", "m-open");
    assertTrue(normalizer.normalize(QqEventNormalizer.EVENT_GROUP_AT, data).isEmpty());
  }
}
