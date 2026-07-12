package io.oryxos.memory;

import io.oryxos.core.memory.MemoryScope;
import io.oryxos.storage.MemoryEntry;
import io.oryxos.storage.MemoryEntryRepository;
import java.util.List;
import org.springframework.data.domain.PageRequest;

/**
 * 档二：长期记忆按条入 memory_entries 表。记忆量变大后的结构化升级，仍零外部依赖（复用既有 SQLite）。
 *
 * <p>契约落地：截断从字符串裁尾变成归档查询的 {@code LIMIT}（核心区用 {@code WHERE scope='CORE'} 全量取， 不受影响——契约二）；检索变成 SQL
 * LIKE（契约四）；每次查库不缓存（契约一）。
 */
public class SqliteMemoryStore implements LongTermMemoryStore {

  private static final String CORE_HEADER = "## 核心记忆";
  private static final String ARCHIVE_HEADER = "## 归档记忆";
  private static final int MAX_ARCHIVE_ROWS = 100;

  private final MemoryEntryRepository repository;

  public SqliteMemoryStore(MemoryEntryRepository repository) {
    this.repository = repository;
  }

  @Override
  public void append(String content, MemoryScope scope) {
    MemoryEntry entry = new MemoryEntry();
    entry.setScope(scope.name());
    entry.setContent(content);
    repository.save(entry);
  }

  @Override
  public String load() {
    String core = render(repository.findByScopeOrderByIdAsc("CORE"));
    // 归档取最近 N，再翻回时间正序拼接——截断只作用归档（契约二）
    List<MemoryEntry> recent =
        repository.findByScopeOrderByIdDesc("ARCHIVAL", PageRequest.of(0, MAX_ARCHIVE_ROWS));
    List<MemoryEntry> ascending = recent.reversed();
    return CORE_HEADER + "\n" + core + "\n" + ARCHIVE_HEADER + "\n" + render(ascending);
  }

  @Override
  public List<String> recallByKeyword(String keyword) {
    return repository.searchArchival("%" + keyword + "%").stream()
        .map(MemoryEntry::getContent)
        .toList();
  }

  private static String render(List<MemoryEntry> entries) {
    return entries.stream()
        .map(e -> "- " + e.getContent())
        .reduce((a, b) -> a + "\n" + b)
        .orElse("");
  }
}
