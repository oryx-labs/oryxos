package io.oryxos.channel.weixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WeixinContextTokenStoreTest {

  @Test
  @DisplayName("记住后能取回")
  void remembersToken() {
    WeixinContextTokenStore store = new WeixinContextTokenStore();
    store.remember("user-1", "ctx-1");
    assertEquals("ctx-1", store.require("user-1"));
  }

  @Test
  @DisplayName("缺失或空白输入不落表")
  void ignoresBlankInput() {
    WeixinContextTokenStore store = new WeixinContextTokenStore();
    store.remember(null, "ctx");
    store.remember("  ", "ctx");
    store.remember("user-1", null);
    store.remember("user-1", "  ");
    assertThrows(IllegalStateException.class, () -> store.require("user-1"));
  }

  @Test
  @DisplayName("表有上界：超出后最早的被淘汰，最近的在")
  void isBounded() {
    WeixinContextTokenStore store = new WeixinContextTokenStore();
    int cap = WeixinContextTokenStore.MAX_TRACKED_USERS;
    for (int i = 0; i <= cap; i++) {
      store.remember("user-" + i, "ctx-" + i);
    }
    // 最早那条已被淘汰（用户重发一条即可，报错信息也就是这么写的）
    assertThrows(IllegalStateException.class, () -> store.require("user-0"));
    // 最近的仍在
    assertEquals("ctx-" + cap, store.require("user-" + cap));
  }
}
