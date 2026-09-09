package io.oryxos.channel.mattermost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.channel.ChatKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MattermostEventNormalizerTest {

  private final MattermostEventNormalizer normalizer =
      new MattermostEventNormalizer("ops-mm", "oryxbot");

  @Test
  @DisplayName("DM 频道类型 D → P2P")
  void dm() {
    var msg =
        normalizer.normalize(
            "token=t&user_id=u1&channel_id=c1&post_id=p1&channel_type=D&text=hello");
    assertTrue(msg.isPresent());
    assertEquals(ChatKind.P2P, msg.get().chatKind());
    assertEquals("hello", msg.get().content());
  }

  @Test
  @DisplayName("频道无 trigger / @ → 丢弃")
  void channelNoMention() {
    assertTrue(
        normalizer
            .normalize("token=t&user_id=u1&channel_id=c1&post_id=p1&channel_type=O&text=hello")
            .isEmpty());
  }

  @Test
  @DisplayName("频道只 @bot 无正文 → 非文本 GROUP，留给媒体解析")
  void channelMentionOnly() {
    var msg =
        normalizer.normalize(
            "token=t&user_id=u1&channel_id=c1&post_id=p1&channel_type=O&text=@oryxbot&trigger_word=@oryxbot");
    assertTrue(msg.isPresent());
    assertEquals(ChatKind.GROUP, msg.get().chatKind());
    assertEquals("", msg.get().content());
    assertTrue(msg.get().mentionedBot());
    assertTrue(msg.get().attachments().isEmpty());
  }

  @Test
  @DisplayName("频道 @bot → GROUP")
  void channelMention() {
    var msg =
        normalizer.normalize(
            "token=t&user_id=u1&channel_id=c1&post_id=p1&channel_type=O&text=@oryxbot%20hi");
    assertTrue(msg.isPresent());
    assertEquals(ChatKind.GROUP, msg.get().chatKind());
    assertEquals("hi", msg.get().content());
  }
}
