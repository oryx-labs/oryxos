package io.oryxos.memory;

import io.oryxos.core.memory.MemoryScope;
import java.util.ArrayList;
import java.util.List;

/**
 * 内存后端：进程内 Map 存储，满足四条契约。两个用途——契约测试里代替 Mem0 档验证"这一档守不守规矩" （Mem0 真实 REST 交互由 Mem0MemoryStoreTest 单独
 * mock），以及作为门面/工具测试的轻量基建。
 */
public class InMemoryMemoryStore implements LongTermMemoryStore {

  private static final String CORE_HEADER = "## 核心记忆";
  private static final String ARCHIVE_HEADER = "## 归档记忆";
  private static final int MAX_ARCHIVE_ROWS = 100;

  private final List<String> core = new ArrayList<>();
  private final List<String> archive = new ArrayList<>();

  @Override
  public void append(String content, MemoryScope scope) {
    (scope == MemoryScope.CORE ? core : archive).add(content);
  }

  @Override
  public String load() {
    List<String> recentArchive =
        archive.size() <= MAX_ARCHIVE_ROWS
            ? archive
            : archive.subList(archive.size() - MAX_ARCHIVE_ROWS, archive.size());
    return CORE_HEADER
        + "\n"
        + String.join("\n", core)
        + "\n"
        + ARCHIVE_HEADER
        + "\n"
        + String.join("\n", recentArchive);
  }

  @Override
  public List<String> recallByKeyword(String keyword) {
    return archive.stream().filter(line -> line.contains(keyword)).toList();
  }
}
