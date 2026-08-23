package io.oryxos.memory;

import io.oryxos.core.agent.ToolExecutionContext;
import io.oryxos.core.memory.MemoryEntryView;
import io.oryxos.core.memory.MemoryRecallCapability;
import io.oryxos.core.memory.MemoryScope;
import io.oryxos.storage.MemoryEntry;
import io.oryxos.storage.MemoryEntryRepository;
import java.util.List;
import java.util.Locale;
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

  /**
   * 当前作用域（015 FR-014）：有 Agent 上下文时记忆跟 Agent 走；无上下文（非 Agent 触发的直接调用、单测） 回退 '__global__'，与 markdown
   * 档全局文件回退语义对齐。
   */
  private static String agentName() {
    String agent = ToolExecutionContext.agentName();
    return agent == null || agent.isBlank() ? MemoryEntry.GLOBAL_AGENT : agent;
  }

  @Override
  public MemoryEntryView append(String content, MemoryScope scope) {
    MemoryEntry entry = new MemoryEntry();
    entry.setAgentName(agentName());
    entry.setScope(scope.name());
    entry.setContent(content);
    repository.save(entry);
    // 不依赖 save 返回值（测试 mock 可能返回 null）；createdAt 未填时用 now，对账不依赖精确时刻
    java.time.Instant time =
        entry.getCreatedAt() != null ? entry.getCreatedAt() : java.time.Instant.now();
    return new MemoryEntryView(entry.getContent(), time);
  }

  @Override
  public String load() {
    String agent = agentName();
    String core = render(repository.findByAgentNameAndScopeOrderByIdAsc(agent, "CORE"));
    // 归档取最近 N，再翻回时间正序拼接——截断只作用归档（契约二）
    List<MemoryEntry> recent =
        repository.findByAgentNameAndScopeOrderByIdDesc(
            agent, "ARCHIVAL", PageRequest.of(0, MAX_ARCHIVE_ROWS));
    List<MemoryEntry> ascending = recent.reversed();
    return CORE_HEADER + "\n" + core + "\n" + ARCHIVE_HEADER + "\n" + render(ascending);
  }

  @Override
  public List<String> recallByKeyword(String keyword) {
    // 大小写统一（FR-002）：JPQL 侧 LOWER(content)，这里把关键词也压小写，语义与 markdown 档一致
    String pattern = "%" + keyword.toLowerCase(Locale.ROOT) + "%";
    return repository.searchArchival(agentName(), pattern).stream()
        .map(MemoryEntry::getContent)
        .toList();
  }

  @Override
  public MemoryRecallCapability capabilities() {
    return MemoryRecallCapability.HYBRID_BUILTIN;
  }

  /** 归档区全量（未截断，id 正序 = 写入顺序）；created_at 为条目时间。 */
  @Override
  public List<MemoryEntryView> archivalEntries() {
    return repository.findByAgentNameAndScopeOrderByIdAsc(agentName(), "ARCHIVAL").stream()
        .map(e -> new MemoryEntryView(e.getContent(), e.getCreatedAt()))
        .toList();
  }

  private static String render(List<MemoryEntry> entries) {
    return entries.stream()
        .map(e -> "- " + e.getContent())
        .reduce((a, b) -> a + "\n" + b)
        .orElse("");
  }
}
