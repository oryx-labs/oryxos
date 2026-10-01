package io.oryxos.channel.douyin;

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

class DouyinEventNormalizerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String OPERATOR = "op-open";
  private DouyinEventNormalizer normalizer;

  @BeforeEach
  void setUp() {
    normalizer = new DouyinEventNormalizer("ops-douyin", OPERATOR);
  }

  @Test
  @DisplayName("用户→经营者文本私信 → P2P")
  void receiveText() {
    Optional<InboundMessage> msg =
        normalizer.normalize(payload(EVENT_RECEIVE, "u1", OPERATOR, "你好"));
    assertTrue(msg.isPresent());
    InboundMessage m = msg.get();
    assertEquals(ChatKind.P2P, m.chatKind());
    assertEquals("你好", m.content());
    assertEquals("user:u1", m.chatId());
    assertEquals("msg-1", m.messageId());
    assertTrue(m.textual());
  }

  @Test
  @DisplayName("非经营者收件 → 丢弃")
  void wrongToDropped() {
    assertTrue(normalizer.normalize(payload(EVENT_RECEIVE, "u1", "other", "hi")).isEmpty());
  }

  @Test
  @DisplayName("图片等非文本 → textual=false")
  void nonText() {
    ObjectNode root = payload(EVENT_SEND, "u1", OPERATOR, null);
    ((ObjectNode) root.get("content")).put("message_type", "image");
    Optional<InboundMessage> msg = normalizer.normalize(root);
    assertTrue(msg.isPresent());
    assertFalse(msg.get().textual());
  }

  @Test
  @DisplayName("verify_webhook → 丢弃（由 Adapter 处理）")
  void verifyDropped() {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("event", DouyinEventNormalizer.EVENT_VERIFY);
    assertTrue(normalizer.normalize(root).isEmpty());
  }

  private static final String EVENT_RECEIVE = DouyinEventNormalizer.EVENT_RECEIVE;
  private static final String EVENT_SEND = DouyinEventNormalizer.EVENT_SEND;

  private ObjectNode payload(String event, String from, String to, String text) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("event", event);
    root.put("from_user_id", from);
    root.put("to_user_id", to);
    root.put("client_key", "ck");
    ObjectNode content = root.putObject("content");
    content.put("conversation_short_id", "conv-1");
    content.put("server_message_id", "msg-1");
    content.put("conversation_type", 1);
    content.put("message_type", "text");
    if (text != null) {
      content.put("text", text);
    }
    return root;
  }
}
