package io.oryxos.memory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.core.embedding.TextEmbedder;
import io.oryxos.core.memory.MemoryEntryView;
import io.oryxos.storage.MemoryVectorEntity;
import io.oryxos.storage.MemoryVectorRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 向量索引维护（015 T013/T014）：零丢失、队满丢弃、对账幂等。 */
class MemoryVectorIndexTest {

  private static final Executor DIRECT = Runnable::run;
  private static final String AGENT = "ops-agent";

  /** 确定性假 embedder：维度 2，向量由内容长度导出。 */
  private static TextEmbedder fakeEmbedder(String modelId) {
    return new TextEmbedder() {
      @Override
      public float[] embed(String text) {
        return new float[] {text.length(), 1};
      }

      @Override
      public String modelId() {
        return modelId;
      }

      @Override
      public int dimensions() {
        return 2;
      }
    };
  }

  /** 背靠内存 List 的有状态 mock 仓库。 */
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
              data.removeIf(row -> !row.getEmbeddingModel().equals(model));
              return null;
            })
        .when(repo)
        .deleteByEmbeddingModelNot(anyString());
    return repo;
  }

  private static MemoryVectorEntity row(
      String agent, String hash, String content, String model, int dim) {
    MemoryVectorEntity e = new MemoryVectorEntity();
    e.setAgentName(agent);
    e.setEntryHash(hash);
    e.setContent(content);
    e.setEmbedding(new byte[] {0, 0, 0, 0});
    e.setDim(dim);
    e.setEmbeddingModel(model);
    return e;
  }

  @Test
  @DisplayName("enqueue 即向量化落表（direct executor）_内容与时间齐备")
  void enqueueIndexesEntry() {
    List<MemoryVectorEntity> data = new ArrayList<>();
    MemoryVectorIndex index = new MemoryVectorIndex(fakeRepo(data), fakeEmbedder("m1"), DIRECT);
    Instant time = Instant.parse("2026-08-20T10:00:00Z");

    index.enqueue(AGENT, new MemoryEntryView("归档条目", time));

    assertEquals(1, data.size());
    MemoryVectorEntity saved = data.get(0);
    assertEquals(MemoryVectorIndex.entryHash(AGENT, "归档条目"), saved.getEntryHash());
    assertEquals("归档条目", saved.getContent());
    assertEquals("m1", saved.getEmbeddingModel());
    assertEquals(2, saved.getDim());
    assertEquals(time, saved.getEntryTime());
  }

  @Test
  @DisplayName("embedder 异常_不抛出不落行（零丢失指本体，索引静默）")
  void embedderFailureIsSwallowed() {
    List<MemoryVectorEntity> data = new ArrayList<>();
    TextEmbedder broken =
        new TextEmbedder() {
          @Override
          public float[] embed(String text) {
            throw new IllegalStateException("embedding 服务不可用");
          }

          @Override
          public String modelId() {
            return "m1";
          }

          @Override
          public int dimensions() {
            return 2;
          }
        };
    MemoryVectorIndex index = new MemoryVectorIndex(fakeRepo(data), broken, DIRECT);

    assertDoesNotThrow(() -> index.enqueue(AGENT, new MemoryEntryView("条目", null)));
    assertTrue(data.isEmpty());
  }

  @Test
  @DisplayName("执行器拒绝（队满/已停）_enqueue 静默丢弃")
  void rejectedExecutionIsSilentlyDiscarded() {
    Executor rejecting =
        task -> {
          throw new RejectedExecutionException("queue full");
        };
    MemoryVectorIndex index =
        new MemoryVectorIndex(fakeRepo(new ArrayList<>()), fakeEmbedder("m1"), rejecting);

    assertDoesNotThrow(() -> index.enqueue(AGENT, new MemoryEntryView("条目", null)));
  }

  @Test
  @DisplayName("对账_补缺失清孤儿并整体重建旧模型行_重复执行幂等（FR-007）")
  void reconcileIsIdempotentAndRebuildsOnModelChange() {
    List<MemoryVectorEntity> data = new ArrayList<>();
    MemoryEntryView kept = new MemoryEntryView("已索引条目", null);
    MemoryEntryView missing = new MemoryEntryView("缺失条目", null);
    String keptHash = MemoryVectorIndex.entryHash(AGENT, kept.content());
    data.add(row(AGENT, keptHash, kept.content(), "m1", 2)); // 现模型现维度已索引 → 保留
    data.add(row(AGENT, "orphan-hash", "本体已删的条目", "m1", 2)); // 孤儿 → 清
    data.add(row(AGENT, "stale-hash", "旧模型残留", "m0", 2)); // 旧模型 → 整体重建时清
    MemoryVectorIndex index = new MemoryVectorIndex(fakeRepo(data), fakeEmbedder("m1"), DIRECT);
    List<MemoryEntryView> archival = List.of(kept, missing);

    index.reconcile(AGENT, archival);

    assertEquals(2, data.size(), "保留已索引 + 补缺失；孤儿与旧模型行被清");
    assertTrue(data.stream().anyMatch(r -> r.getEntryHash().equals(keptHash)));
    assertTrue(data.stream().anyMatch(r -> r.getContent().equals("缺失条目")));
    assertTrue(data.stream().allMatch(r -> r.getEmbeddingModel().equals("m1")));

    index.reconcile(AGENT, archival); // 幂等：再跑一遍状态不变
    assertEquals(2, data.size());
  }
}
