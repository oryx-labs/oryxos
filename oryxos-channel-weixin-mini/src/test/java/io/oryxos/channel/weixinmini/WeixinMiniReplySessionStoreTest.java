package io.oryxos.channel.weixinmini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WeixinMiniReplySessionStoreTest {

  @Test
  @DisplayName("窗外与超 5 条硬拒绝")
  void windowAndCap() {
    WeixinMiniReplySessionStore store = new WeixinMiniReplySessionStore();
    String chat = "mini:wxapp:user:u1";
    long t0 = 1_000_000L;
    store.rememberInbound(chat, t0);
    WeixinMiniReplySessionStore.Session s = store.requireForReply(chat, t0 + 1000);
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
                    chat, t0 + WeixinMiniReplySessionStore.SESSION_WINDOW.toMillis() + 1));
    assertTrue(expired.getMessage().contains("48"));
    assertEquals(5, WeixinMiniReplySessionStore.MAX_REPLIES_PER_WINDOW);
    assertEquals(s.replyCount(), 0);
  }
}
