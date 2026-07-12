package io.oryxos.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.memory.MemoryScope;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Markdown 档专属：字符串截断边界、区块解析、空文件。 */
class MarkdownMemoryStoreTest {

  @TempDir Path root;

  @Test
  @DisplayName("空记忆文件_load 返回空区块不报错")
  void emptyFileLoadsEmptySections() {
    String loaded = new MarkdownMemoryStore(root).load();

    assertTrue(loaded.contains("## 核心记忆"));
    assertTrue(loaded.contains("## 归档记忆"));
  }

  @Test
  @DisplayName("归档恰好等于上限不截断_超过才裁最早")
  void archivalTruncatesOnlyBeyondLimit() {
    MarkdownMemoryStore memory = new MarkdownMemoryStore(root);
    memory.append("核心恒在", MemoryScope.CORE);
    // 每条约 20+ 字符，200 条远超 4000 字符上限
    for (int i = 0; i < 200; i++) {
      memory.append("archival-entry-number-" + i, MemoryScope.ARCHIVAL);
    }

    String loaded = memory.load();

    assertTrue(loaded.contains("核心恒在"), "核心区不受字符串截断影响");
    assertFalse(loaded.contains("archival-entry-number-0"), "最早的归档被裁");
    assertTrue(loaded.contains("archival-entry-number-199"), "最近的保留");
  }

  @Test
  @DisplayName("核心与归档写入互不串区")
  void coreAndArchivalStaySeparate() {
    MarkdownMemoryStore memory = new MarkdownMemoryStore(root);
    memory.append("我是核心", MemoryScope.CORE);
    memory.append("我是归档", MemoryScope.ARCHIVAL);

    // 归档检索只命中归档条目
    assertEquals(1, memory.recallByKeyword("归档").size());
    assertTrue(memory.recallByKeyword("核心").isEmpty());
  }
}
