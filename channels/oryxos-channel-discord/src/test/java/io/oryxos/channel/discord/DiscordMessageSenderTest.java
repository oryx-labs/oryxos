package io.oryxos.channel.discord;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DiscordMessageSenderTest {

  @Test
  @DisplayName("segment 按块切分")
  void segmentChunks() {
    List<String> parts = DiscordMessageSender.segment("abcdefghij", 4);
    assertEquals(List.of("abcd", "efgh", "ij"), parts);
  }

  @Test
  @DisplayName("HTTP 错误体解析 message/code")
  void rejectApiError() {
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () ->
                DiscordMessageSender.rejectApiError(
                    403, "{\"message\":\"Missing Access\",\"code\":50001}"));
    assertTrue(ex.getMessage().contains("Missing Access"));
    assertTrue(ex.getMessage().contains("50001"));
  }

  @Test
  @DisplayName("空 body 仍抛 HTTP 状态")
  void rejectEmptyBody() {
    assertDoesNotThrow(
        () -> {
          try {
            DiscordMessageSender.rejectApiError(500, "");
          } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("500"));
          }
        });
  }
}
