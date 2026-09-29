package io.oryxos.channel.alipay;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AlipayReplySessionStoreTest {

  @Test
  @DisplayName("无入站上下文则拒绝回复")
  void missing() {
    AlipayReplySessionStore store = new AlipayReplySessionStore();
    assertThrows(IllegalStateException.class, () -> store.requireForReply("c1", 1_000L));
  }

  @Test
  @DisplayName("超过 48h 拒绝")
  void expired() {
    AlipayReplySessionStore store = new AlipayReplySessionStore();
    long now = 1_000_000L;
    store.rememberInbound("c1", now);
    long later = now + Duration.ofHours(49).toMillis();
    assertThrows(IllegalStateException.class, () -> store.requireForReply("c1", later));
  }

  @Test
  @DisplayName("窗内可回复")
  void ok() {
    AlipayReplySessionStore store = new AlipayReplySessionStore();
    long now = 1_000_000L;
    store.rememberInbound("c1", now);
    store.requireForReply("c1", now + 1_000L);
  }
}
