package io.oryxos.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.core.embedding.TextEmbedder;
import io.oryxos.core.embedding.VectorCodec;
import io.oryxos.core.memory.MemoryEntryView;
import io.oryxos.core.memory.MemoryRecallCapability;
import io.oryxos.core.memory.MemoryScope;
import io.oryxos.storage.MemoryVectorEntity;
import io.oryxos.storage.MemoryVectorRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 三路召回引擎（015 T015/T016）：融合语义、模式边界（未配置/降级/top-k）、权重生效。 */
class MemoryRecallEngineTest {

  private static final String AGENT = "ops-agent";
  private static final String MODEL = "mock/deterministic";
  private static final double[] EQUAL_WEIGHTS = {1.0, 1.0, 1.0};
  private static final int TOP_K = 20;

  /** 查表式确定性 embedder：未登记的文本得零向量（与任何向量正交）。 */
  private static final class TableEmbedder implements TextEmbedder {
    private final Map<String, float[]> table = new HashMap<>();

    TableEmbedder put(String text, float... vector) {
      table.put(text, vector);
      return this;
    }

    @Override
    public float[] embed(String text) {
      return table.getOrDefault(text, new float[] {0, 0});
    }

    @Override
    public String modelId() {
      return MODEL;
    }

    @Override
    public int dimensions() {
      return 2;
    }
  }

  /** 归档条目背靠 List 的假后端（HYBRID_BUILTIN 档形状）。 */
  private static final class FakeStore implements LongTermMemoryStore {
    private final List<MemoryEntryView> archival = new ArrayList<>();

    FakeStore add(String content, Instant time) {
      archival.add(new MemoryEntryView(content, time));
      return this;
    }

    @Override
    public MemoryEntryView append(String content, MemoryScope scope) {
      MemoryEntryView view = new MemoryEntryView(content, Instant.now());
      archival.add(view);
      return view;
    }

    @Override
    public String load() {
      return "";
    }

    @Override
    public List<String> recallByKeyword(String keyword) {
      String needle = keyword.toLowerCase(Locale.ROOT);
      return archival.stream()
          .map(MemoryEntryView::content)
          .filter(line -> line.toLowerCase(Locale.ROOT).contains(needle))
          .toList();
    }

    @Override
    public MemoryRecallCapability capabilities() {
      return MemoryRecallCapability.HYBRID_BUILTIN;
    }

    @Override
    public List<MemoryEntryView> archivalEntries() {
      return List.copyOf(archival);
    }
  }

  private static MemoryVectorRepository repoWithVectors(
      TableEmbedder embedder, FakeStore store, String... indexedContents) {
    List<MemoryVectorEntity> rows = new ArrayList<>();
    for (String content : indexedContents) {
      MemoryVectorEntity row = new MemoryVectorEntity();
      row.setAgentName(AGENT);
      row.setEntryHash(MemoryVectorIndex.entryHash(AGENT, content));
      row.setContent(content);
      float[] vector = embedder.embed(content);
      row.setEmbedding(VectorCodec.encode(vector));
      row.setDim(vector.length);
      row.setEmbeddingModel(MODEL);
      rows.add(row);
    }
    MemoryVectorRepository repo = mock(MemoryVectorRepository.class);
    when(repo.findByAgentName(anyString())).thenReturn(rows);
    return repo;
  }

  @Test
  @DisplayName("语义路命中措辞不同的条目_无共同关键词也能排第一（US1 场景 1）")
  void semanticRouteHitsParaphrasedEntry() {
    TableEmbedder embedder =
        new TableEmbedder().put("发布流程在灰度环节踩雷，回滚后改为分批放量", 1, 0).put("上次那个部署的坑怎么处理的", 0.9f, 0.1f);
    FakeStore store =
        new FakeStore()
            .add("发布流程在灰度环节踩雷，回滚后改为分批放量", Instant.parse("2026-08-01T00:00:00Z"))
            .add("午饭吃了牛肉面", Instant.parse("2026-08-19T00:00:00Z"));
    MemoryVectorRepository repo = repoWithVectors(embedder, store, "发布流程在灰度环节踩雷，回滚后改为分批放量");
    MemoryRecallEngine engine = new MemoryRecallEngine(repo, embedder, EQUAL_WEIGHTS, TOP_K);

    List<String> lines = engine.recall(store, AGENT, "上次那个部署的坑怎么处理的");

    assertEquals("发布流程在灰度环节踩雷，回滚后改为分批放量", lines.get(0), "语义命中赢过纯时间新近");
  }

  @Test
  @DisplayName("关键词路命中精确代号_大小写不同也命中")
  void keywordRouteHitsExactCode() {
    TableEmbedder embedder = new TableEmbedder().put("ops-4721", 0, 1);
    FakeStore store =
        new FakeStore()
            .add("工单 OPS-4721 已升级到二线", Instant.parse("2026-08-10T00:00:00Z"))
            .add("无关条目", Instant.parse("2026-08-11T00:00:00Z"));
    MemoryRecallEngine engine =
        new MemoryRecallEngine(repoWithVectors(embedder, store), embedder, EQUAL_WEIGHTS, TOP_K);

    List<String> lines = engine.recall(store, AGENT, "ops-4721");

    assertEquals("工单 OPS-4721 已升级到二线", lines.get(0), "关键词命中条目排最前");
  }

  @Test
  @DisplayName("相关性相当时较新者排前（关键词路内按新近排）")
  void recencyBreaksTieAmongEquallyRelevant() {
    TableEmbedder embedder = new TableEmbedder();
    FakeStore store =
        new FakeStore()
            .add("需求评审 alpha 一期结论", Instant.parse("2026-08-01T00:00:00Z"))
            .add("需求评审 alpha 二期结论", Instant.parse("2026-08-15T00:00:00Z"));
    MemoryRecallEngine engine =
        new MemoryRecallEngine(repoWithVectors(embedder, store), embedder, EQUAL_WEIGHTS, TOP_K);

    List<String> lines = engine.recall(store, AGENT, "需求评审 alpha");

    assertEquals("需求评审 alpha 二期结论", lines.get(0), "同等命中，较新者排前");
  }

  @Test
  @DisplayName("recency 权重调大改变排序（SC-004 权重系数生效）")
  void recencyWeightBoostChangesOrdering() {
    // A 语义更近但更旧；B 语义稍远但更新——等权时语义占先，recency 加权后反转
    TableEmbedder embedder =
        new TableEmbedder()
            .put("查询词", 1, 0)
            .put("语义最近的旧条目", 0.99f, 0.14f)
            .put("语义稍远的新条目", 0.95f, 0.31f);
    FakeStore store =
        new FakeStore()
            .add("语义最近的旧条目", Instant.parse("2026-08-01T00:00:00Z"))
            .add("语义稍远的新条目", Instant.parse("2026-08-19T00:00:00Z"));
    MemoryVectorRepository repo = repoWithVectors(embedder, store, "语义最近的旧条目", "语义稍远的新条目");

    List<String> equal =
        new MemoryRecallEngine(repo, embedder, EQUAL_WEIGHTS, TOP_K).recall(store, AGENT, "查询词");
    List<String> boosted =
        new MemoryRecallEngine(repo, embedder, new double[] {1.0, 1.0, 2.0}, TOP_K)
            .recall(store, AGENT, "查询词");

    assertEquals("语义最近的旧条目", equal.get(0), "等权基线：语义近者在前");
    assertEquals("语义稍远的新条目", boosted.get(0), "recency 权重翻倍后新条目反超");
  }

  @Test
  @DisplayName("未配置 embedding_recall 直通关键词旧行为（FR-013 字节级兼容）")
  void unconfiguredModeKeepsLegacyKeywordBehavior() {
    FakeStore store =
        new FakeStore()
            .add("含 needle 的旧条目", Instant.parse("2026-08-01T00:00:00Z"))
            .add("不相关", Instant.parse("2026-08-10T00:00:00Z"))
            .add("含 needle 的新条目", Instant.parse("2026-08-19T00:00:00Z"));
    MemoryRecallEngine engine =
        new MemoryRecallEngine(mock(MemoryVectorRepository.class), null, EQUAL_WEIGHTS, 1);

    List<String> lines = engine.recall(store, AGENT, "needle");

    assertEquals(store.recallByKeyword("needle"), lines, "与旧行为逐项一致：写入序、不融合");
    assertEquals(2, lines.size(), "top-k=1 在未配置模式不生效（不截断）");
    assertFalse(lines.contains(MemoryRecallEngine.DEGRADE_NOTICE), "未配置模式绝不追加标注");
  }

  @Test
  @DisplayName("已配置但语义路失败_两路降级且尾行标注；健康时无标注（FR-003）")
  void degradeAppendsTrailingNoticeOnlyWhenConfiguredAndBroken() {
    FakeStore store = new FakeStore().add("含 needle 条目", Instant.parse("2026-08-01T00:00:00Z"));
    TextEmbedder broken =
        new TextEmbedder() {
          @Override
          public float[] embed(String text) {
            throw new IllegalStateException("embedding 服务不可达");
          }

          @Override
          public String modelId() {
            return MODEL;
          }

          @Override
          public int dimensions() {
            return 2;
          }
        };
    MemoryVectorRepository repo = mock(MemoryVectorRepository.class);
    when(repo.findByAgentName(anyString())).thenReturn(List.of());

    List<String> degraded =
        new MemoryRecallEngine(repo, broken, EQUAL_WEIGHTS, TOP_K).recall(store, AGENT, "needle");
    List<String> healthy =
        new MemoryRecallEngine(repo, new TableEmbedder(), EQUAL_WEIGHTS, TOP_K)
            .recall(store, AGENT, "needle");

    assertEquals(MemoryRecallEngine.DEGRADE_NOTICE, degraded.getLast(), "降级标注在结果尾行");
    assertTrue(degraded.contains("含 needle 条目"), "关键词 + 时间两路照常返回");
    assertFalse(healthy.contains(MemoryRecallEngine.DEGRADE_NOTICE), "健康时无标注");
  }

  @Test
  @DisplayName("top-k 截断仅配置态生效")
  void topKAppliesOnlyInConfiguredMode() {
    TableEmbedder embedder = new TableEmbedder();
    FakeStore store = new FakeStore();
    for (int i = 0; i < 5; i++) {
      store.add("条目-" + i, Instant.parse("2026-08-0" + (i + 1) + "T00:00:00Z"));
    }
    MemoryRecallEngine engine =
        new MemoryRecallEngine(repoWithVectors(embedder, store), embedder, EQUAL_WEIGHTS, 2);

    List<String> lines = engine.recall(store, AGENT, "条目");

    assertEquals(2, lines.size(), "配置态融合结果截断至 top-k");
  }
}
