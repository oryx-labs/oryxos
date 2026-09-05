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

    registry.register(key, "s-old");
    assertEquals("s-old", registry.current(key).orElseThrow());

    registry.register(key, "s-new");
    registry.unregister(key, "s-old");
    assertEquals("s-new", registry.current(key).orElseThrow());

    registry.unregister(key, "s-new");
    assertTrue(registry.current(key).isEmpty());
  }
}
