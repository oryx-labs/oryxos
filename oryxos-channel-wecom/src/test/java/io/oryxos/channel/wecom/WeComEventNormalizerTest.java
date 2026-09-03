package io.oryxos.channel.wecom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundMessage;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WeComEventNormalizerTest {

  private final WeComEventNormalizer normalizer = new WeComEventNormalizer("ops-wecom");
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  @DisplayName("单聊文本 → P2P，chatId=userid")
  void singleText() throws Exception {
    ObjectNode body = mapper.createObjectNode();
    body.put("msgid", "m1");
    body.put("chattype", "single");
    body.put("msgtype", "text");
    body.putObject("from").put("userid", "u1");
    body.putObject("text").put("content", "hello");

    InboundMessage msg = normalizer.normalize(body).orElseThrow();
    assertEquals(ChatKind.P2P, msg.chatKind());
    assertEquals("u1", msg.chatId());
    assertEquals("u1", msg.userId());
    assertEquals("hello", msg.content());
    assertTrue(msg.textual());
    assertFalse(msg.mentionedBot());
  }

  @Test
  @DisplayName("群聊文本剥离前导 @，mentionedBot=true")
  void groupTextStripsAt() throws Exception {
    ObjectNode body = mapper.createObjectNode();
    body.put("msgid", "m2");
    body.put("chattype", "group");
    body.put("chatid", "g1");
    body.put("msgtype", "text");
    body.putObject("from").put("userid", "u1");
    body.putObject("text").put("content", "@RobotA 今天天气");

    InboundMessage msg = normalizer.normalize(body).orElseThrow();
    assertEquals(ChatKind.GROUP, msg.chatKind());
    assertEquals("g1", msg.chatId());
    assertEquals("今天天气", msg.content());
    assertTrue(msg.mentionedBot());
  }

  @Test
  @DisplayName("非文本仍构造消息但 textual=false")
  void nonText() throws Exception {
    ObjectNode body = mapper.createObjectNode();
    body.put("msgid", "m3");
    body.put("chattype", "single");
    body.put("msgtype", "image");
    body.putObject("from").put("userid", "u1");
    body.putObject("image").put("url", "https://x").put("aeskey", "AESKEY123");

    InboundMessage msg = normalizer.normalize(body).orElseThrow();
    assertFalse(msg.textual());
    assertEquals("", msg.content());
    assertEquals(1, msg.attachments().size());
    assertEquals("https://x", msg.attachments().get(0).url());
    assertEquals("AESKEY123", msg.attachments().get(0).reference());
  }

  @Test
  @DisplayName("缺字段 → empty")
  void missingFields() throws Exception {
    ObjectNode body = mapper.createObjectNode();
    body.put("msgid", "m4");
    Optional<InboundMessage> msg = normalizer.normalize(body);
    assertTrue(msg.isEmpty());
  }
}
