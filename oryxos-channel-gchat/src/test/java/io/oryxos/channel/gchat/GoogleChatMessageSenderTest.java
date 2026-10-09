package io.oryxos.channel.gchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GoogleChatMessageSenderTest {

  /** 平台单条正文上限（Chat API 单条消息正文）。 */
  private static final int PLATFORM_CAP = 4096;

  @Test
  @DisplayName("默认分段上限不超过平台单条上限")
  void defaultChunkSizeFitsUnderThePlatformCap() {
    assertTrue(
        GoogleChatMessageSender.DEFAULT_CHUNK_SIZE <= PLATFORM_CAP,
        "默认分段 " + GoogleChatMessageSender.DEFAULT_CHUNK_SIZE + " 超过平台上限 " + PLATFORM_CAP);
    assertTrue(GoogleChatMessageSender.DEFAULT_CHUNK_SIZE > 0);
  }

  @Test
  @DisplayName("超长回复被切成多段，每段不超上限，拼接后一字不差")
  void longReplyIsSplitAndReassembles() {
    String reply = "字".repeat(PLATFORM_CAP * 2 + 137);
    List<String> parts =
        GoogleChatMessageSender.segment(reply, GoogleChatMessageSender.DEFAULT_CHUNK_SIZE);
    assertTrue(parts.size() >= 3, "应切成多段，实际 " + parts.size());
    for (String part : parts) {
      assertTrue(
          part.length() <= GoogleChatMessageSender.DEFAULT_CHUNK_SIZE, "有段超上限: " + part.length());
    }
    assertEquals(reply, String.join("", parts), "分段拼接必须与原回复一致");
  }

  @Test
  @DisplayName("短回复只切一段，不额外分包")
  void shortReplyStaysWhole() {
    assertEquals(
        List.of("你好"),
        GoogleChatMessageSender.segment("你好", GoogleChatMessageSender.DEFAULT_CHUNK_SIZE));
  }

  @Test
  @DisplayName("空文本仍产出一段（保持「必有回复」语义）")
  void emptyYieldsOneEmptyPart() {
    assertEquals(
        List.of(""),
        GoogleChatMessageSender.segment("", GoogleChatMessageSender.DEFAULT_CHUNK_SIZE));
    assertEquals(
        List.of(""),
        GoogleChatMessageSender.segment(null, GoogleChatMessageSender.DEFAULT_CHUNK_SIZE));
  }
}
