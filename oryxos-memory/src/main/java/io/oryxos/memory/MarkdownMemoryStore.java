package io.oryxos.memory;

import io.oryxos.core.memory.MemoryScope;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * 档一（默认）：长期记忆存 `.oryxos/memory/MEMORY.md` 一个 Markdown 文件，按 `## 核心记忆` / `## 归档记忆` 两个区块组织。零依赖、人可读、git
 * 可跟踪。
 *
 * <p>契约落地：load 每次 Files.readString 不缓存（契约一）；truncateIfNeeded 只接归档区那段字符串裁尾、
 * 物理上碰不到核心区（契约二）；recallByKeyword 只搜归档区（契约四）。
 */
public class MarkdownMemoryStore implements LongTermMemoryStore {

  private static final String CORE_HEADER = "## 核心记忆";
  private static final String ARCHIVE_HEADER = "## 归档记忆";
  private static final int MAX_ARCHIVE_CHARS = 4000;

  private final Path memoryFile;

  public MarkdownMemoryStore(Path oryxosRoot) {
    this.memoryFile = oryxosRoot.resolve("memory").resolve("MEMORY.md");
  }

  @Override
  public void append(String content, MemoryScope scope) {
    String header = scope == MemoryScope.CORE ? CORE_HEADER : ARCHIVE_HEADER;
    String entry = "- [" + LocalDate.now() + "] " + content;
    String raw = read();
    String core = extractSection(raw, CORE_HEADER);
    String archive = extractSection(raw, ARCHIVE_HEADER);
    if (scope == MemoryScope.CORE) {
      core = core.isEmpty() ? entry : core + "\n" + entry;
    } else {
      archive = archive.isEmpty() ? entry : archive + "\n" + entry;
    }
    write(CORE_HEADER + "\n" + core + "\n" + ARCHIVE_HEADER + "\n" + archive);
  }

  @Override
  public String load() {
    String raw = read(); // 每次重新读——契约一
    String core = extractSection(raw, CORE_HEADER); // 核心区：完整返回
    String archive = truncateIfNeeded(extractSection(raw, ARCHIVE_HEADER));
    return CORE_HEADER + "\n" + core + "\n" + ARCHIVE_HEADER + "\n" + archive;
  }

  @Override
  public List<String> recallByKeyword(String keyword) {
    return extractSection(read(), ARCHIVE_HEADER)
        .lines()
        .filter(line -> !line.isBlank() && line.contains(keyword))
        .toList();
  }

  /** 只裁归档段字符串，核心区不在入参里——契约二靠物理隔离保证。 */
  private static String truncateIfNeeded(String archive) {
    if (archive.length() <= MAX_ARCHIVE_CHARS) {
      return archive;
    }
    return archive.substring(archive.length() - MAX_ARCHIVE_CHARS);
  }

  private static String extractSection(String raw, String header) {
    int start = raw.indexOf(header);
    if (start < 0) {
      return "";
    }
    int contentStart = start + header.length();
    int nextCore = raw.indexOf(CORE_HEADER, contentStart);
    int nextArchive = raw.indexOf(ARCHIVE_HEADER, contentStart);
    int end = raw.length();
    if (nextCore >= 0) {
      end = Math.min(end, nextCore);
    }
    if (nextArchive >= 0) {
      end = Math.min(end, nextArchive);
    }
    return raw.substring(contentStart, end).strip();
  }

  private String read() {
    if (!Files.isRegularFile(memoryFile)) {
      return "";
    }
    try {
      return Files.readString(memoryFile);
    } catch (IOException e) {
      throw new UncheckedIOException("读取 MEMORY.md 失败", e);
    }
  }

  private void write(String content) {
    try {
      Path parent = memoryFile.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(memoryFile, content);
    } catch (IOException e) {
      throw new UncheckedIOException("写入 MEMORY.md 失败", e);
    }
  }
}
