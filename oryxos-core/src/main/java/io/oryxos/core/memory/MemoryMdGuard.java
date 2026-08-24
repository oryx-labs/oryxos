package io.oryxos.core.memory;

import java.nio.file.Path;
import java.util.Locale;

/**
 * 长期记忆文件只能经 {@code save_memory} 写入——{@code MarkdownMemoryStore#sanitizeEntryContent}
 * 只覆盖那条路径；其它入口直写会绕过分区头消毒（#163），污染核心/归档召回。
 */
public final class MemoryMdGuard {

  /** 小写文件名；比较时用 {@link Locale#ROOT}。 */
  private static final String MEMORY_FILE_LOWER = "memory.md";

  private MemoryMdGuard() {}

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "文件名是 ASCII「MEMORY.md」；Locale.ROOT 大小写折叠仅用于 Windows 大小写不敏感路径，非安全边界上的任意 Unicode 文本")
  public static void rejectMutation(String path) {
    if (path == null || path.isBlank()) {
      return;
    }
    Path name = Path.of(path).getFileName();
    if (name != null && MEMORY_FILE_LOWER.equals(name.toString().toLowerCase(Locale.ROOT))) {
      throw new IllegalArgumentException("拒绝直接改写 MEMORY.md，请使用 save_memory: " + path);
    }
  }
}
