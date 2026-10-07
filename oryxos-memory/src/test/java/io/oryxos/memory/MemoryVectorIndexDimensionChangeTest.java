package io.oryxos.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.core.embedding.TextEmbedder;
import io.oryxos.core.memory.MemoryEntryView;
import io.oryxos.core.memory.MemoryRecallCapability;
import io.oryxos.core.memory.MemoryScope;
import io.oryxos.storage.MemoryVectorEntity;
import io.oryxos.storage.MemoryVectorRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 015 FR-007 的「维度」一半：向量模型/维度不一致 MUST 自动重建。modelId 不变而维度变了（同一 provider/model 换了
 * dimensions，或上游同名模型换了隐层宽度）时，索引行必须被判为陈旧并重建——否则语义路按 `row.dim == queryDim` 过滤后恒空，recall
 * 既不报错也不标注降级（FR-003 的降级标注只覆盖「向量化不可用」），措辞不同的旧记忆再也召不回。
 */
class MemoryVectorIndexDimensionChangeTest {

  private static final String AGENT = "ops-agent";

  /** 同一 provider/model 的前后两次取值——维度变了，modelId 没变。 */
  private static final String MODEL = "qwen/text-embedding-v3";

  private static final String SEMANTIC_ENTRY = "发布流程在灰度环节踩雷，回滚后改为分批放量";
  private static final String QUERY = "上次那个部署的坑怎么处理的";
  private static final Executor DIRECT = Runnable::run;

  /** 内容感知的确定性 embedder：维度可变、modelId 固定。 */
  private static final class DimEmbedder implements TextEmbedder {
    private final int dim;
    private int calls;

    DimEmbedder(int dim) {
      this.dim = dim;
    }

    int calls() {
      return calls;
    }

    @Override
    public float[] embed(String text) {
      calls++;
      float[] vector = new float[dim];
      if (text != null && text.contains("灰度环节踩雷")) {
        vector[0] = 0.99f;
        if (dim > 1) {
          vector[1] = 0.14f;
        }
      } else if (text != null && text.contains("部署的坑")) {
        vector[0] = 1f;
      } else if (dim > 1) {
        vector[1] = 1f;
      } else {
        vector[0] = 1f;
      }
      return vector;
    }

    @Override
    public String modelId() {
      return MODEL;
    }

    @Override
    public int dimensions() {
      return dim;
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
      String needle = keyword.toLowerCase(java.util.Locale.ROOT);
      return archival.stream()
          .map(MemoryEntryView::content)
          .filter(content -> content.toLowerCase(java.util.Locale.ROOT).contains(needle))
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

  private static FakeStore store() {
    Instant base = Instant.parse("2026-08-20T10:00:00Z");
    return new FakeStore()
        .add(SEMANTIC_ENTRY, base)
        .add("例行巡检无异常 A", base.plusSeconds(60))
        .add("例行巡检无异常 B", base.plusSeconds(120))
        .add("例行巡检无异常 C", base.plusSeconds(180));
  }

  /** 背靠内存 List 的有状态 mock 仓库（含模型与条目两级删除）。 */
  private static MemoryVectorRepository fakeRepo(List<MemoryVectorEntity> data) {
    MemoryVectorRepository repo = mock(MemoryVectorRepository.class);
    when(repo.save(any()))
        .thenAnswer(
            inv -> {
              MemoryVectorEntity e = inv.getArgument(0);
              data.removeIf(
                  row ->
                      row.getAgentName().equals(e.getAgentName())
                          && row.getEntryHash().equals(e.getEntryHash()));
              data.add(e);
              return e;
            });
    when(repo.findByAgentName(anyString()))
        .thenAnswer(
            inv -> {
              String agent = inv.getArgument(0);
              return data.stream().filter(row -> row.getAgentName().equals(agent)).toList();
            });
    when(repo.findByAgentNameAndEntryHash(anyString(), anyString()))
        .thenAnswer(
            inv -> {
              String agent = inv.getArgument(0);
              String hash = inv.getArgument(1);
              return data.stream()
                  .filter(
                      row -> row.getAgentName().equals(agent) && row.getEntryHash().equals(hash))
                  .findFirst();
            });
    org.mockito.Mockito.doAnswer(
            inv -> {
              String agent = inv.getArgument(0);
              Collection<?> hashes = inv.getArgument(1);
              data.removeIf(
                  row -> row.getAgentName().equals(agent) && hashes.contains(row.getEntryHash()));
              return null;
            })
        .when(repo)
        .deleteByAgentNameAndEntryHashIn(anyString(), any());
    org.mockito.Mockito.doAnswer(
            inv -> {
              String model = inv.getArgument(0);
              data.removeIf(row -> !model.equals(row.getEmbeddingModel()));
              return null;
            })
        .when(repo)
        .deleteByEmbeddingModelNot(anyString());
    return repo;
  }

  private static List<Integer> dims(List<MemoryVectorEntity> data) {
    return data.stream().map(MemoryVectorEntity::getDim).toList();
  }

  private static List<String> recall(
      List<MemoryVectorEntity> data, TextEmbedder embedder, LongTermMemoryStore store) {
    return new MemoryRecallEngine(fakeRepo(data), embedder, new double[] {1, 1, 1}, 3)
        .recall(store, AGENT, QUERY);
  }

  @Test
  @DisplayName("modelId 不变而维度 2→3_对账按维度重建陈旧行_语义路仍召回（FR-007）")
  void reconcileRebuildsIndexWhenDimensionsChangeUnderTheSameModelId() {
    List<MemoryVectorEntity> data = new ArrayList<>();
    FakeStore store = store();
    MemoryVectorIndex writer = new MemoryVectorIndex(fakeRepo(data), new DimEmbedder(2), DIRECT);
    for (MemoryEntryView entry : store.archivalEntries()) {
      writer.enqueue(AGENT, entry);
    }
    assertEquals(List.of(2, 2, 2, 2), dims(data));
    assertTrue(recall(data, new DimEmbedder(2), store).contains(SEMANTIC_ENTRY), "对照组：语义路可用");

    // 第 2 次启动：同一 modelId、维度变成 3 —— 启动对账必须整体重建
    MemoryVectorIndex reader = new MemoryVectorIndex(fakeRepo(data), new DimEmbedder(3), DIRECT);
    reader.reconcile(AGENT, store.archivalEntries());

    List<String> lines = recall(data, new DimEmbedder(3), store);
    assertTrue(lines.contains(SEMANTIC_ENTRY), "维度变化后语义路必须召回措辞不同的那条记忆，实际 " + lines);
    assertEquals(List.of(3, 3, 3, 3), dims(data), "维度不一致的行必须按当前 embedder 重建");
    assertFalse(lines.contains(MemoryRecallEngine.DEGRADE_NOTICE), "语义路可用，不该标注降级");
  }

  @Test
  @DisplayName("modelId 与维度都没变_对账不重建不重算")
  void reconcileKeepsRowsWhenModelAndDimensionsAreUnchanged() {
    List<MemoryVectorEntity> data = new ArrayList<>();
    FakeStore store = store();
    MemoryVectorIndex writer = new MemoryVectorIndex(fakeRepo(data), new DimEmbedder(2), DIRECT);
    for (MemoryEntryView entry : store.archivalEntries()) {
      writer.enqueue(AGENT, entry);
    }
    DimEmbedder readerEmbedder = new DimEmbedder(2);

    MemoryVectorIndex reader = new MemoryVectorIndex(fakeRepo(data), readerEmbedder, DIRECT);
    reader.reconcile(AGENT, store.archivalEntries());
    reader.reconcile(AGENT, store.archivalEntries());

    assertEquals(List.of(2, 2, 2, 2), dims(data), "行没有被清掉重建");
    assertEquals(0, readerEmbedder.calls(), "已是最新：对账不重新向量化");
  }

  @Test
  @DisplayName("对账之外_同条目重写入队也刷新维度陈旧的行")
  void enqueueRebuildsRowWhoseDimensionsAreStale() {
    List<MemoryVectorEntity> data = new ArrayList<>();
    FakeStore store = store();
    MemoryEntryView entry = store.archivalEntries().get(0);
    MemoryVectorIndex writer = new MemoryVectorIndex(fakeRepo(data), new DimEmbedder(2), DIRECT);
    writer.enqueue(AGENT, entry);
    assertEquals(List.of(2), dims(data));

    new MemoryVectorIndex(fakeRepo(data), new DimEmbedder(3), DIRECT).enqueue(AGENT, entry);

    assertEquals(List.of(3), dims(data), "同一条目的陈旧维行必须被当前维向量顶替");
  }
}
