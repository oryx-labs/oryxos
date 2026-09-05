package io.oryxos.core.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReconnectBackoffTest {

  @Test
  @DisplayName("指数退避并封顶")
  void exponentialCap() {
    assertEquals(2_000L, ReconnectBackoff.delayMs(0, 2_000L, 60_000L, 5));
    assertEquals(4_000L, ReconnectBackoff.delayMs(1, 2_000L, 60_000L, 5));
    assertEquals(8_000L, ReconnectBackoff.delayMs(2, 2_000L, 60_000L, 5));
    assertEquals(60_000L, ReconnectBackoff.delayMs(10, 2_000L, 60_000L, 5));
    assertEquals(2_000L, ReconnectBackoff.delayMs(-1, 2_000L, 60_000L, 5));
  }
}
