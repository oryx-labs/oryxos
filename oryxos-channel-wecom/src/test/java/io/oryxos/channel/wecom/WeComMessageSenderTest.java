package io.oryxos.channel.wecom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WeComMessageSenderTest {

  @Test
  @DisplayName("发送 markdown 帧并带 chat_type")
  void sendMarkdownWithChatType() {
    List<ObjectNode> frames = new ArrayList<>();
    AtomicReference<String> guarded = new AtomicReference<>();
    WeComMessageSender sender =
        new WeComMessageSender(
            frames::add, url -> guarded.set(url), WeComChannelAdapter.OUTBOUND_URL, 100);
    sender.rememberChatType("g1", 2);
    sender.send("g1", "hi");

    assertEquals(WeComChannelAdapter.OUTBOUND_URL, guarded.get());
    assertEquals(1, frames.size());
    ObjectNode frame = frames.get(0);
    assertEquals("aibot_send_msg", frame.path("cmd").asText());
    assertEquals("g1", frame.path("body").path("chatid").asText());
    assertEquals(2, frame.path("body").path("chat_type").asInt());
    assertEquals("markdown", frame.path("body").path("msgtype").asText());
    assertEquals("hi", frame.path("body").path("markdown").path("content").asText());
  }

  @Test
  @DisplayName("超长文本分段")
  void segmentsLongText() {
    List<ObjectNode> frames = new ArrayList<>();
    WeComMessageSender sender =
        new WeComMessageSender(frames::add, url -> {}, WeComChannelAdapter.OUTBOUND_URL, 4);
    sender.send("u1", "abcdefgh");
    assertEquals(2, frames.size());
    assertEquals("abcd", frames.get(0).path("body").path("markdown").path("content").asText());
    assertEquals("efgh", frames.get(1).path("body").path("markdown").path("content").asText());
  }

  @Test
  @DisplayName("segment 工具方法边界")
  void segmentHelper() {
    assertEquals(List.of(""), WeComMessageSender.segment("", 10));
    assertTrue(WeComMessageSender.segment("abc", 10).equals(List.of("abc")));
  }
}
