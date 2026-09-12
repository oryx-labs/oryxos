package io.oryxos.channel.weixinmp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WeixinMpReplySessionStoreTest {

  @Test
  @DisplayName("窗外与超 5 条硬拒绝")
  void windowAndCap() {
    WeixinMpReplySessionStore store = new WeixinMpReplySessionStore();
    String chat = "mp:wxapp:user:u1";
    long t0 = 1_000_000L;
    store.rememberInbound(chat, t0);
    WeixinMpReplySessionStore.Session s = store.requireForReply(chat, t0 + 1000);
    for (int i = 0; i < 5; i++) {
      store.markReplied(chat, store.requireForReply(chat, t0 + 1000 + i));
    }
    IllegalStateException over =
        assertThrows(IllegalStateException.class, () -> store.requireForReply(chat, t0 + 2000));
    assertTrue(over.getMessage().contains("上限"));

    store.rememberInbound(chat, t0);
    IllegalStateException expired =
        assertThrows(
            IllegalStateException.class,
            () ->
                store.requireForReply(
                    chat, t0 + WeixinMpReplySessionStore.SESSION_WINDOW.toMillis() + 1));
    assertTrue(expired.getMessage().contains("48"));
    assertEquals(5, WeixinMpReplySessionStore.MAX_REPLIES_PER_WINDOW);
    assertEquals(s.replyCount(), 0);
  }
}
