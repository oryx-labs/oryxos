package io.oryxos.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.core.memory.MemoryScope;
import io.oryxos.storage.MemoryEntry;
import io.oryxos.storage.MemoryEntryRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SC-002/003 兼容性专项（015 T019）：embedding 未配置时，新实现的 recall 输出与「旧算法参考实现」
 * 逐项逐字节一致——唯一获准的差异是大小写统一（FR-002，Clarify 已白名单）。参考实现内嵌于本测试： 它是升级前 MarkdownMemoryStore /
 * SqliteMemoryStore 检索逻辑的行为快照（+获准的大小写修正）， 与生产代码零共享——生产侧任何行为漂移都会在这里对不上。
 */
class RecallBackwardCompatTest {

  @TempDir Path root;

  private static final List<String> DATASET =
      List.of(
          "工单 OPS-4721 已升级到二线",
          "发布流程在灰度环节踩雷，回滚后改为分批放量",
          "OPS-4721 复盘：根因是配置漂移",
          "例行巡检无异常",
          "用户反馈搜索白屏，关联工单 ops-9900");

  private static final List<String> QUERIES =
      List.of("OPS-4721", "ops-4721", "灰度", "巡检", "工单", "不存在的词");

  // ---------- markdown 档 ----------

  @Test
  @DisplayName("markdown 档_未配置模式输出与旧算法逐项一致（除大小写统一）")
  void markdownUnconfiguredMatchesLegacyAlgorithm() throws Exception {
    MemoryServiceImpl service = new MemoryServiceImpl(new MarkdownMemoryStore(root));
    for (String entry : DATASET) {
      service.remember(entry, MemoryScope.ARCHIVAL);
    }
    service.remember("核心区不参与检索的事实", MemoryScope.CORE);
    String raw = Files.readString(root.resolve("memory").resolve("MEMORY.md"));

    for (String query : QUERIES) {
      assertEquals(
          legacyMarkdownRecall(raw, query),
          service.recall(query),
          "query=" + query + " 与旧算法（+大小写修正）逐项一致");
    }
  }

  @Test
  @DisplayName("markdown 档_未配置模式无降级标注_无 top-k 截断")
  void markdownUnconfiguredHasNoAnnotationOrTruncation() {
    MemoryServiceImpl service = new MemoryServiceImpl(new MarkdownMemoryStore(root));
    for (int i = 0; i < 50; i++) {
      service.remember("批量条目 bulk-" + i, MemoryScope.ARCHIVAL);
    }

    List<String> lines = service.recall("bulk-");

    assertEquals(50, lines.size(), "全量返回：旧行为无 top-k 截断");
    assertFalse(lines.contains(MemoryRecallEngine.DEGRADE_NOTICE), "未配置模式绝不追加标注");
  }

  /**
   * 旧版 MarkdownMemoryStore.recallByKeyword 行为快照：提取 `## 归档记忆` 区段 → 非空行 contains 过滤、 保持文件行序。原版为区分大小写的
   * {@code line.contains(keyword)}；此处按获准修正改为不区分大小写， 除此之外一字不改。
   */
  private static List<String> legacyMarkdownRecall(String raw, String keyword) {
    String needle = keyword.toLowerCase(Locale.ROOT);
    return extractArchiveSection(raw)
        .lines()
        .filter(line -> !line.isBlank() && line.toLowerCase(Locale.ROOT).contains(needle))
        .toList();
  }

  /** 旧版区段提取快照（独立成行的 `## 归档记忆` 头到下一个区块头/文件尾）。 */
  private static String extractArchiveSection(String raw) {
    Pattern header = Pattern.compile("(?m)^## 归档记忆\\s*$");
    Matcher start = header.matcher(raw);
    if (!start.find()) {
      return "";
    }
    int contentStart = start.end();
    int end = raw.length();
    Matcher nextCore = Pattern.compile("(?m)^## 核心记忆\\s*$").matcher(raw);
    if (nextCore.find(contentStart)) {
      end = Math.min(end, nextCore.start());
    }
    Matcher nextArchive = header.matcher(raw);
    if (nextArchive.find(contentStart)) {
      end = Math.min(end, nextArchive.start());
    }
    return raw.substring(contentStart, end).strip();
  }

  // ---------- sqlite 档 ----------

  @Test
  @DisplayName("sqlite 档_存量全局数据未配置模式输出与旧查询语义逐项一致")
  void sqliteUnconfiguredMatchesLegacyQuerySemantics() {
    List<MemoryEntry> data = new ArrayList<>();
    MemoryServiceImpl service = new MemoryServiceImpl(new SqliteMemoryStore(statefulRepo(data)));
    for (String entry : DATASET) {
      service.remember(entry, MemoryScope.ARCHIVAL);
    }
    service.remember("核心区事实", MemoryScope.CORE);

    for (String query : QUERIES) {
      assertEquals(
          legacySqliteRecall(data, query),
          service.recall(query),
          "query=" + query + " 与旧 JPQL 语义（LIKE + id 正序）逐项一致");
    }
  }

  /**
   * 旧版 SqliteMemoryStore 检索行为快照：{@code scope='ARCHIVAL' AND content LIKE %kw% ORDER BY id
   * ASC}，配合调用方把关键词压小写。 这里按 Java 侧折叠 + 字面包含表达该语义：ASCII 与 SQLite 的 LIKE 同判，非 ASCII 只有 Java
   * 侧折叠做得到（SQLite 的 {@code lower()} 只折 ASCII），所以这一档的一致性由折叠规则本身保证，不靠 SQL。
   */
  private static List<String> legacySqliteRecall(List<MemoryEntry> data, String keyword) {
    String needle = keyword.toLowerCase(Locale.ROOT);
    return data.stream()
        .filter(e -> "ARCHIVAL".equals(e.getScope()))
        .filter(e -> e.getContent().toLowerCase(Locale.ROOT).contains(needle))
        .sorted(Comparator.comparingLong(MemoryEntry::getId))
        .map(MemoryEntry::getContent)
        .toList();
  }

  private static MemoryEntryRepository statefulRepo(List<MemoryEntry> data) {
    long[] seq = {0};
    MemoryEntryRepository repo = mock(MemoryEntryRepository.class);
    when(repo.save(any()))
        .thenAnswer(
            inv -> {
              MemoryEntry e = inv.getArgument(0);
              try {
                var field = MemoryEntry.class.getDeclaredField("id");
                field.setAccessible(true);
                field.set(e, ++seq[0]);
              } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException(ex);
              }
              data.add(e);
              return e;
            });
    when(repo.findByAgentNameAndScopeOrderByIdAsc(anyString(), anyString()))
        .thenAnswer(
            inv -> {
              String agent = inv.getArgument(0);
              String scope = inv.getArgument(1);
              return data.stream()
                  .filter(e -> e.getAgentName().equals(agent) && e.getScope().equals(scope))
                  .toList();
            });
    return repo;
  }
}
