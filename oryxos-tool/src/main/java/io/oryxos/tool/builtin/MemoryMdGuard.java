package io.oryxos.tool.builtin;

import java.nio.file.Path;

/**
 * 长期记忆文件只能经 {@code save_memory} 写入——{@code MarkdownMemoryStore#sanitizeEntryContent}
 * 只覆盖那条路径；文件工具直写会绕过分区头消毒（#163），污染核心/归档召回。
 */
final class MemoryMdGuard {

  private static final String MEMORY_FILE = "MEMORY.md";

  private MemoryMdGuard() {}

  static void rejectMutation(String path) {
    if (path == null || path.isBlank()) {
      return;
    }
    Path name = Path.of(path).getFileName();
    if (name != null && MEMORY_FILE.equalsIgnoreCase(name.toString())) {
      throw new IllegalArgumentException("拒绝直接改写 MEMORY.md，请使用 save_memory: " + path);
    }
  }
}
