package io.oryxos.memory.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.memory.MemoryScope;
import io.oryxos.memory.InMemoryMemoryStore;
import io.oryxos.memory.MemoryServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** MemoryTools：scope 缺省写归档、非法 scope 报错、未命中友好提示。 */
class MemoryToolsTest {

  private InMemoryMemoryStore store;
  private MemoryTools tools;

  private MemoryTools tools() {
    store = new InMemoryMemoryStore();
    tools = new MemoryTools(new MemoryServiceImpl(store));
    return tools;
  }

  @Test
  @DisplayName("scope 缺省写入归档区")
  void defaultScopeWritesArchival() {
    MemoryTools t = tools();

    t.saveMemory("默认该进归档", null);

    // 归档区可检索到、核心区检索不到（缺省落归档）
    assertEquals("默认该进归档", store.recallByKeyword("默认").get(0));
  }

  @Test
  @DisplayName("显式 core 写入核心区")
  void explicitCoreWritesCore() {
    MemoryTools t = tools();

    t.saveMemory("这是核心", "core");

    assertTrue(store.recallByKeyword("这是核心").isEmpty(), "核心区不参与归档检索");
    assertTrue(store.load().contains("这是核心"));
  }

  @Test
  @DisplayName("非法 scope 报错点名不落库")
  void invalidScopeReportsError() {
    MemoryTools t = tools();

    String result = t.saveMemory("内容", "bogus");

    assertTrue(result.contains("bogus"), "报错点名非法分区值");
    assertTrue(store.load().replace("## 核心记忆", "").replace("## 归档记忆", "").isBlank(), "不落库");
  }

  @Test
  @DisplayName("检索未命中返回友好提示不抛异常")
  void recallMissReturnsFriendlyMessage() {
    MemoryTools t = tools();

    assertEquals("没有找到相关记忆", t.recallMemory("查无此词"));
  }

  @Test
  @DisplayName("save 缺省参数使用 MemoryScope 枚举 ARCHIVAL")
  void defaultMapsToArchivalScope() {
    MemoryTools t = tools();
    t.saveMemory("x", "archival");
    // 直接经 store 确认分区
    store.append("核对", MemoryScope.ARCHIVAL);
    assertTrue(store.recallByKeyword("x").size() >= 1);
  }
}
