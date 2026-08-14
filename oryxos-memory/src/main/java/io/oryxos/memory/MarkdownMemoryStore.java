package io.oryxos.memory;

import io.oryxos.core.agent.ToolExecutionContext;
import io.oryxos.core.memory.MemoryScope;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 档一（默认）：长期记忆存 Markdown 文件，按 `## 核心记忆` / `## 归档记忆` 两个区块组织。零依赖、人可读、git 可跟踪。
 *
 * <p>Agent 专属（30 节）：目标文件由 {@link ToolExecutionContext#agentName()} 决定——有 Agent 上下文时落
 * `.oryxos/agents/&lt;name&gt;/MEMORY.md`（这个 Agent 自己的成长记录，跟它的 AGENT.md/skills/ 同目录，合宪法四）；无上下文（非
 * Agent 触发的直接调用、单测）时回退全局 `.oryxos/memory/MEMORY.md`，保持向后兼容。Agent 名做安全校验，非法段一律回退全局，防目录穿越。
 *
 * <p>契约落地：load 每次 Files.readString 不缓存（契约一）；truncateIfNeeded 只接归档区那段字符串裁尾、
 * 物理上碰不到核心区（契约二）；recallByKeyword 只搜归档区（契约四）。
 */
public class MarkdownMemoryStore implements LongTermMemoryStore {

  private static final String CORE_HEADER = "## 核心记忆";
  private static final String ARCHIVE_HEADER = "## 归档记忆";
  private static final int MAX_ARCHIVE_CHARS = 4000;
  private static final Pattern SAFE_AGENT = Pattern.compile("[A-Za-z0-9_-]+");
  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  /** 每个 MEMORY.md 一把锁（静态：多个 store 实例指向同一文件也互斥），条目数上限 = Agent 数 + 1，不会膨胀。 */
  private static final Map<Path, Object> FILE_LOCKS = new ConcurrentHashMap<>();

  private final Path oryxosRoot;

  public MarkdownMemoryStore(Path oryxosRoot) {
    this.oryxosRoot = oryxosRoot;
  }

  /** 当前该读写哪个 MEMORY.md：有合法 Agent 上下文 → 该 Agent 目录；否则回退全局。 */
  private Path memoryFile() {
    String agent = ToolExecutionContext.agentName();
    if (agent != null && SAFE_AGENT.matcher(agent).matches()) {
      return oryxosRoot.resolve("agents").resolve(agent).resolve("MEMORY.md");
    }
    return oryxosRoot.resolve("memory").resolve("MEMORY.md");
  }

  @Override
  public void append(String content, MemoryScope scope) {
    String entry = "- [" + LocalDateTime.now().format(TIMESTAMP) + "] " + content;
    Path file = memoryFile();
    // 读-改-写必须整段互斥：并发会话/定时任务同时 append 时，不加锁会互相覆盖对方刚写的条目。
    synchronized (lockFor(file)) {
      String raw = read(file);
      String core = extractSection(raw, CORE_HEADER);
      String archive = extractSection(raw, ARCHIVE_HEADER);
      if (scope == MemoryScope.CORE) {
        core = core.isEmpty() ? entry : core + "\n" + entry;
      } else {
        archive = archive.isEmpty() ? entry : archive + "\n" + entry;
      }
      write(file, CORE_HEADER + "\n" + core + "\n" + ARCHIVE_HEADER + "\n" + archive);
    }
  }

  private static Object lockFor(Path file) {
    return FILE_LOCKS.computeIfAbsent(file.toAbsolutePath().normalize(), key -> new Object());
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
    return read(memoryFile());
  }

  private static String read(Path file) {
    if (!Files.isRegularFile(file)) {
      return "";
    }
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException("读取 MEMORY.md 失败", e);
    }
  }

  /** 先写临时文件再原子改名：进程在写一半时被杀也不会留下截断的 MEMORY.md（长期记忆不可再生，不容半个文件）。 */
  private static void write(Path file, String content) {
    try {
      Path parent = file.toAbsolutePath().getParent();
      if (parent == null) { // 只有根路径无父目录；MEMORY.md 不可能是根，这里是防御 + 满足空指针静态分析
        throw new IllegalArgumentException("MEMORY.md 路径没有父目录: " + file);
      }
      Files.createDirectories(parent);
      Path tmp = Files.createTempFile(parent, "MEMORY", ".tmp");
      try {
        Files.writeString(tmp, content);
        try {
          Files.move(
              tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
          Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); // 极少数文件系统不支持原子移动，退而求其次
        }
      } finally {
        Files.deleteIfExists(tmp);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("写入 MEMORY.md 失败", e);
    }
  }
}
