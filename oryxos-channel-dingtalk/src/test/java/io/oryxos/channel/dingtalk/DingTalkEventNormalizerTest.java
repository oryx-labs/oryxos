package io.oryxos.channel.dingtalk;

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

class DingTalkEventNormalizerTest {

  private final DingTalkEventNormalizer normalizer = new DingTalkEventNormalizer("ops-dingtalk");
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  @DisplayName("单聊文本 → P2P，chatId=conversationId")
  void singleText() {
    ObjectNode body = mapper.createObjectNode();
    body.put("msgId", "m1");
    body.put("conversationType", "1");
    body.put("conversationId", "conv-p2p");
    body.put("senderId", "u1");
    body.put("msgtype", "text");
    body.putObject("text").put("content", "hello");

    InboundMessage msg = normalizer.normalize(body).orElseThrow();
    assertEquals(ChatKind.P2P, msg.chatKind());
    assertEquals("conv-p2p", msg.chatId());
    assertEquals("u1", msg.userId());
    assertEquals("hello", msg.content());
    assertTrue(msg.textual());
    assertFalse(msg.mentionedBot());
  }

  @Test
  @DisplayName("群聊文本剥离前导 @，mentionedBot=true")
  void groupTextStripsAt() {
    ObjectNode body = mapper.createObjectNode();
    body.put("msgId", "m2");
    body.put("conversationType", "2");
    body.put("conversationId", "conv-grp");
    body.put("senderId", "u1");
    body.put("isInAtList", true);
    body.put("msgtype", "text");
    body.putObject("text").put("content", "@RobotA 今天天气");

    InboundMessage msg = normalizer.normalize(body).orElseThrow();
    assertEquals(ChatKind.GROUP, msg.chatKind());
    assertEquals("conv-grp", msg.chatId());
    assertEquals("今天天气", msg.content());
    assertTrue(msg.mentionedBot());
  }

  @Test
  @DisplayName("群聊未 @ 机器人 → empty")
  void groupWithoutAtDiscarded() {
    ObjectNode body = mapper.createObjectNode();
    body.put("msgId", "m2b");
    body.put("conversationType", "2");
    body.put("conversationId", "conv-grp");
    body.put("senderId", "u1");
    body.put("isInAtList", false);
    body.put("msgtype", "text");
    body.putObject("text").put("content", "旁聊");

    assertTrue(normalizer.normalize(body).isEmpty());
  }

  @Test
  @DisplayName("picture + downloadCode（官方 Stream 格式）→ 图片附件")
  void pictureWithDownloadCode() {
    ObjectNode body = mapper.createObjectNode();
    body.put("msgId", "m3b");
    body.put("conversationType", "1");
    body.put("conversationId", "conv-p2p");
    body.put("senderId", "u1");
    body.put("msgtype", "picture");
    body.putObject("content")
        .put("pictureDownloadCode", "pWjA****ks=")
        .put("downloadCode", "mIof****JE0E");

    InboundMessage msg = normalizer.normalize(body).orElseThrow();
    assertFalse(msg.textual());
    assertTrue(msg.processable());
    assertEquals(1, msg.attachments().size());
    assertEquals("mIof****JE0E", msg.attachments().get(0).reference());
  }

  @Test
  @DisplayName("非文本仍构造消息但 textual=false；图片带 picURL 附件")
  void nonText() {
    ObjectNode body = mapper.createObjectNode();
    body.put("msgId", "m3");
    body.put("conversationType", "1");
    body.put("conversationId", "conv-p2p");
    body.put("senderId", "u1");
    body.put("msgtype", "picture");
    body.putObject("content").put("picURL", "https://x");

    InboundMessage msg = normalizer.normalize(body).orElseThrow();
    assertFalse(msg.textual());
    assertEquals("", msg.content());
    assertEquals(1, msg.attachments().size());
    assertEquals("https://x", msg.attachments().get(0).url());
  }

  @Test
  @DisplayName("file + downloadCode → TYPE_FILE 附件")
  void fileWithDownloadCode() {
    ObjectNode body = mapper.createObjectNode();
    body.put("msgId", "m3f");
    body.put("conversationType", "1");
    body.put("conversationId", "conv-p2p");
    body.put("senderId", "u1");
    body.put("msgtype", "file");
    body.putObject("content").put("downloadCode", "fileCode123");

    InboundMessage msg = normalizer.normalize(body).orElseThrow();
    assertFalse(msg.textual());
    assertTrue(msg.processable());
    assertEquals(1, msg.attachments().size());
    assertEquals("file", msg.attachments().get(0).type());
    assertEquals("fileCode123", msg.attachments().get(0).reference());
  }

  @Test
  @DisplayName("缺字段 → empty")
  void missingFields() {
    ObjectNode body = mapper.createObjectNode();
    body.put("msgId", "m4");
    Optional<InboundMessage> msg = normalizer.normalize(body);
    assertTrue(msg.isEmpty());
  }
}
