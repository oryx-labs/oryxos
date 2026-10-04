package io.oryxos.core.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ActiveRunRegistryTest {

  @Test
  @DisplayName("register/current；unregister CAS 不误清新任务")
  void registerUnregisterCas() {
    ActiveRunRegistry registry = new ActiveRunRegistry();
    String key = ActiveRunRegistry.chatKey("feishu", "oc_1");

    long oldRun = registry.register(key, "s-old");
    assertEquals("s-old", registry.current(key).orElseThrow());

    long newRun = registry.register(key, "s-new");
    registry.unregister(key, oldRun);
    assertEquals("s-new", registry.current(key).orElseThrow());

    registry.unregister(key, newRun);
    assertTrue(registry.current(key).isEmpty());
  }

  @Test
  @DisplayName("私聊：同一 sessionId 的两次运行，先结束的不清掉后一次的登记")
  void unregisterOnlyClearsItsOwnRunEvenWhenSessionIdRepeats() {
    ActiveRunRegistry registry = new ActiveRunRegistry();
    String key = ActiveRunRegistry.chatKey("feishu", "p2p_1");
    // 私聊按「渠道 + 用户 + Agent」维持连续会话，同一用户两次运行拿到的是同一个 sessionId。
    String sameSession = "feishu:u1:agent";

    long first = registry.register(key, sameSession);
    long second = registry.register(key, sameSession);

    registry.unregister(key, first); // 先发起的那次结束
    assertEquals(sameSession, registry.current(key).orElseThrow(), "后一次仍在跑，登记不该被先结束的那次清掉");

    registry.unregister(key, second);
    assertTrue(registry.current(key).isEmpty());
  }
}
