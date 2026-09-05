package io.oryxos.core.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlaceholderProgressStreamTest {

  @Test
  @DisplayName("fail 含已停止 → 专用停止文案")
  void failStoppedUsesStoppedReply() {
    List<String> sent = new ArrayList<>();
    PlaceholderProgressStream stream =
        new PlaceholderProgressStream(
            (chat, text, replyTo) -> sent.add(text),
            "c1",
            "m1",
            PlaceholderProgressStream.DEFAULT_THINKING,
            PlaceholderProgressStream.DEFAULT_FAILED,
            PlaceholderProgressStream.DEFAULT_STOPPED,
            PlaceholderProgressStream.DEFAULT_HEARTBEAT,
            0L,
            "测");
    stream.start();
    stream.fail("任务已停止");
    assertEquals(2, sent.size());
    assertEquals(PlaceholderProgressStream.DEFAULT_THINKING, sent.get(0));
    assertEquals(PlaceholderProgressStream.DEFAULT_STOPPED, sent.get(1));
  }

  @Test
  @DisplayName("finish 发出终态正文")
  void finishSendsBody() {
    List<String> sent = new ArrayList<>();
    PlaceholderProgressStream stream =
        new PlaceholderProgressStream((chat, text, replyTo) -> sent.add(text), "c1", null, "测");
    stream.start();
    stream.finish("答案");
    assertTrue(sent.contains("答案"));
  }
}
