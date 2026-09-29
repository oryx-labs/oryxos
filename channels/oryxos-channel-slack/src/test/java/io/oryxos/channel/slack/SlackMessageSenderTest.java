package io.oryxos.channel.slack;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SlackMessageSenderTest {

  @Test
  @DisplayName("segment 按块切分")
  void segmentChunks() {
    List<String> parts = SlackMessageSender.segment("abcdefghij", 4);
    assertEquals(List.of("abcd", "efgh", "ij"), parts);
  }

  @Test
  @DisplayName("ok=true 不抛")
  void okTrue() {
    assertDoesNotThrow(
        () -> SlackMessageSender.rejectBusinessError("{\"ok\":true,\"ts\":\"1.1\"}"));
  }

  @Test
  @DisplayName("ok=false 抛业务失败")
  void okFalse() {
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () ->
                SlackMessageSender.rejectBusinessError(
                    "{\"ok\":false,\"error\":\"channel_not_found\"}"));
    assertTrue(ex.getMessage().contains("channel_not_found"));
  }
}
