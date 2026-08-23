package io.oryxos.memory.contract;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.agent.ToolExecutionContext;
import io.oryxos.core.embedding.TextEmbedder;
import io.oryxos.core.memory.MemoryEntryView;
import io.oryxos.core.memory.MemoryRecallCapability;
import io.oryxos.core.memory.MemoryScope;
import io.oryxos.memory.InMemoryMemoryStore;
import io.oryxos.memory.LongTermMemoryStore;
import io.oryxos.memory.MarkdownMemoryStore;
import io.oryxos.memory.MemoryRecallEngine;
import io.oryxos.memory.MemoryServiceImpl;
import io.oryxos.memory.MemoryVectorIndex;
import io.oryxos.memory.SqliteMemoryStore;
import io.oryxos.storage.MemoryEntry;
import io.oryxos.storage.MemoryEntryRepository;
import io.oryxos.storage.MemoryVectorEntity;
import io.oryxos.storage.MemoryVectorRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.domain.Pageable;

/**
 * 015 记忆后端契约套件（contracts/memory-backend.md §2，FR-010）：九条行为不变量对 markdown / sqlite / DELEGATED
 * 桩三档参数化常驻 CI；mem0 真实档由 T024 增挂 @Tag("integration") 分支。 不变量 9（未配置字节级兼容）由 {@link
 * io.oryxos.memory.RecallBackwardCompatTest} 专项锁死。
 *
 * <p>负例（SC-009）：无法兑现分区语义的实现 MUST 在装配期（构造时）抛可读异常、无降维路径—— {@link PartitionlessBackendStub} 钉死该模式。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryBackendContractTest {

  @TempDir Path tempRoot;

  @AfterEach
  void clearContext() {
    ToolExecutionContext.clear();
  }

  // ---------- 三档参数源 ----------

  Stream<Arguments> allTiers() {
    return Stream.of(
        Arguments.of(
            "markdown", (Supplier<LongTermMemoryStore>) () -> new MarkdownMemoryStore(tempRoot)),
        Arguments.of(
            "sqlite", (Supplier<LongTermMemoryStore>) () -> new SqliteMemoryStore(fakeEntryRepo())),
        Arguments.of("delegated(桩)", (Supplier<LongTermMemoryStore>) DelegatedStub::new));
  }

  // ---------- 九条不变量 ----------

  @ParameterizedTest(name = "[{0}]")
  @MethodSource("allTiers")
  @DisplayName("不变量1_写入即可见（不缓存）")
  void appendIsImmediatelyVisible(String name, Supplier<LongTermMemoryStore> factory) {
    LongTermMemoryStore store = factory.get();
    store.append("刚记的事", MemoryScope.ARCHIVAL);

    assertTrue(store.load().contains("刚记的事"), name + ": load 立即可见");
    assertFalse(store.recallByKeyword("刚记的事").isEmpty(), name + ": 检索立即命中");
  }

  @ParameterizedTest(name = "[{0}]")
  @MethodSource("allTiers")
  @DisplayName("不变量2_核心区永不截断_不参与检索_不入向量索引")
  void coreIsNeverTruncatedSearchedOrIndexed(String name, Supplier<LongTermMemoryStore> factory) {
    LongTermMemoryStore store = factory.get();
    MemoryVectorIndex index = mock(MemoryVectorIndex.class);
    MemoryServiceImpl service = new MemoryServiceImpl(store, null, index);
    service.remember("核心事实 corefact 永在场", MemoryScope.CORE);
    for (int i = 0; i < 300; i++) {
      service.remember("归档流水 " + i, MemoryScope.ARCHIVAL);
    }

    assertTrue(store.load().contains("核心事实 corefact 永在场"), name + ": 灌满归档后核心区仍完整");
    assertTrue(store.recallByKeyword("corefact").isEmpty(), name + ": 核心区不参与检索");
    verify(index, never()).enqueue(anyString(), argThatIsCore());
  }

  private static MemoryEntryView argThatIsCore() {
    return org.mockito.ArgumentMatchers.argThat(
        entry -> entry != null && entry.content().contains("corefact"));
  }

  @ParameterizedTest(name = "[{0}]")
  @MethodSource("allTiers")
  @DisplayName("不变量3_scope 显式路由到正确分区")
  void scopeIsExplicitlyRouted(String name, Supplier<LongTermMemoryStore> factory) {
    LongTermMemoryStore store = factory.get();
    store.append("核心内容 alpha", MemoryScope.CORE);
    store.append("归档内容 beta", MemoryScope.ARCHIVAL);

    assertTrue(store.recallByKeyword("alpha").isEmpty(), name + ": 核心词检索不到");
    assertFalse(store.recallByKeyword("beta").isEmpty(), name + ": 归档词可检索");
    String loaded = store.load();
    assertTrue(loaded.contains("核心内容 alpha") && loaded.contains("归档内容 beta"), name);
  }

  @ParameterizedTest(name = "[{0}]")
  @MethodSource("allTiers")
  @DisplayName("不变量4_per-Agent 隔离_A 的检索绝不命中 B")
  void agentsAreIsolated(String name, Supplier<LongTermMemoryStore> factory) {
    LongTermMemoryStore store = factory.get();
    ToolExecutionContext.setAgentName("agent-a");
    store.append("属于 A 的秘密 isolate-needle", MemoryScope.ARCHIVAL);
    ToolExecutionContext.setAgentName("agent-b");

    assertTrue(store.recallByKeyword("isolate-needle").isEmpty(), name + ": B 检索不到 A 的条目");
    assertFalse(store.load().contains("isolate-needle"), name + ": B 的注入也不带 A 的条目");
  }

  @ParameterizedTest(name = "[{0}]")
  @MethodSource("allTiers")
  @DisplayName("不变量5_关键词检索跨档不区分大小写（FR-002）")
  void keywordSearchIsCaseInsensitive(String name, Supplier<LongTermMemoryStore> factory) {
    LongTermMemoryStore store = factory.get();
    store.append("工单 OPS-4721 已升级到二线", MemoryScope.ARCHIVAL);

    assertFalse(store.recallByKeyword("ops-4721").isEmpty(), name + ": 小写命中大写");
    assertFalse(store.recallByKeyword("OPS-4721").isEmpty(), name + ": 原样命中");
  }

  @ParameterizedTest(name = "[{0}]")
  @MethodSource("allTiers")
  @DisplayName("不变量6_降级可读_语义缺席其余照常（HYBRID 尾行标注 / DELEGATED 可读异常）")
  void degradeIsReadable(String name, Supplier<LongTermMemoryStore> factory) {
    LongTermMemoryStore store = factory.get();
    store.append("含 needle 的归档条目", MemoryScope.ARCHIVAL);
    if (store.capabilities() == MemoryRecallCapability.DELEGATED) {
      ((DelegatedStub) store).failing = true;
      IllegalStateException ex =
          assertThrows(IllegalStateException.class, () -> store.recallByKeyword("needle"));
      assertTrue(ex.getMessage().contains("不可达"), name + ": 故障信息可读");
      return;
    }
    MemoryServiceImpl service =
        new MemoryServiceImpl(
            store, new MemoryRecallEngine(emptyVectorRepo(), brokenEmbedder(), EQUAL, 20), null);

    List<String> lines = service.recall("needle");

    assertEquals(MemoryRecallEngine.DEGRADE_NOTICE, lines.getLast(), name + ": 尾行降级标注");
    assertTrue(lines.stream().anyMatch(l -> l.contains("needle")), name + ": 关键词+时间照常返回");
  }

  @ParameterizedTest(name = "[{0}]")
  @MethodSource("allTiers")
  @DisplayName("不变量7_零丢失_append 不因向量化异常失败（FR-005）")
  void appendNeverFailsOnIndexingErrors(String name, Supplier<LongTermMemoryStore> factory) {
    LongTermMemoryStore store = factory.get();
    if (store.capabilities() == MemoryRecallCapability.DELEGATED) {
      return; // DELEGATED 档不建本地索引，无此故障面
    }
    MemoryVectorIndex brokenIndex =
        new MemoryVectorIndex(emptyVectorRepo(), brokenEmbedder(), Runnable::run);
    MemoryServiceImpl service = new MemoryServiceImpl(store, null, brokenIndex);

    assertDoesNotThrow(() -> service.remember("必须落库的条目", MemoryScope.ARCHIVAL), name);
    assertTrue(store.load().contains("必须落库的条目"), name + ": 本体已写入");
  }

  @ParameterizedTest(name = "[{0}]")
  @MethodSource("allTiers")
  @DisplayName("不变量8_确定性_同输入恒同输出（SC-004/005）")
  void recallIsDeterministic(String name, Supplier<LongTermMemoryStore> factory) {
    LongTermMemoryStore store = factory.get();
    MemoryServiceImpl service = deterministicService(store);
    service.remember("发布流程在灰度环节踩雷", MemoryScope.ARCHIVAL);
    service.remember("工单 OPS-4721 已升级", MemoryScope.ARCHIVAL);
    service.remember("例行巡检无异常", MemoryScope.ARCHIVAL);

    assertEquals(service.recall("OPS-4721"), service.recall("OPS-4721"), name + ": 恒同输出");
    assertEquals(service.recall("灰度"), service.recall("灰度"), name);
  }

  // ---------- 负例：分区必选（SC-009） ----------

  @Test
  @DisplayName("负例_无法兑现分区语义的后端_装配期可读拒绝且无降维路径")
  void partitionlessBackendIsRejectedAtAssembly() {
    IllegalStateException ex =
        assertThrows(IllegalStateException.class, PartitionlessBackendStub::new);

    assertTrue(ex.getMessage().contains("分区"), "拒绝原因点名分区语义");
    assertTrue(ex.getMessage().contains("拒绝"), "明示装配期拒绝而非运行时降维");
  }

  /**
   * 分区坏桩：模拟无法映射 core/archival 的外部记忆服务。契约要求（Clarify-R2）此类实现在装配期
   * （构造时）自我拒绝——绝不提供「按声明降维」的运行路径，也不把失败推迟到首次调用。
   */
  private static final class PartitionlessBackendStub {
    private PartitionlessBackendStub() {
      throw new IllegalStateException(
          "记忆后端 partitionless-stub 无法映射 core/archival 分区语义，装配期拒绝（无降维路径，需更换后端或升级服务）");
    }
  }

  // ---------- 桩与夹具 ----------

  private static final double[] EQUAL = {1.0, 1.0, 1.0};

  /** DELEGATED 桩：自带检索的假后端（per-Agent 隔离 + 确定性命中 + 可配置故障）。 */
  private static final class DelegatedStub implements LongTermMemoryStore {
    private final Map<String, List<String>> coreByAgent = new HashMap<>();
    private final Map<String, List<String>> archiveByAgent = new HashMap<>();
    boolean failing;

    private static String agent() {
      String agent = ToolExecutionContext.agentName();
      return agent == null || agent.isBlank() ? "__global__" : agent;
    }

    @Override
    public MemoryEntryView append(String content, MemoryScope scope) {
      Map<String, List<String>> target = scope == MemoryScope.CORE ? coreByAgent : archiveByAgent;
      target.computeIfAbsent(agent(), key -> new ArrayList<>()).add(content);
      return new MemoryEntryView(content, java.time.Instant.now());
    }

    @Override
    public String load() {
      return "## 核心记忆\n"
          + String.join("\n", coreByAgent.getOrDefault(agent(), List.of()))
          + "\n## 归档记忆\n"
          + String.join("\n", archiveByAgent.getOrDefault(agent(), List.of()));
    }

    @Override
    public List<String> recallByKeyword(String keyword) {
      if (failing) {
        throw new IllegalStateException("外部记忆服务不可达（桩）：连接被拒绝");
      }
      String needle = keyword.toLowerCase(Locale.ROOT);
      return archiveByAgent.getOrDefault(agent(), List.of()).stream()
          .filter(line -> line.toLowerCase(Locale.ROOT).contains(needle))
          .toList();
    }

    @Override
    public MemoryRecallCapability capabilities() {
      return MemoryRecallCapability.DELEGATED;
    }

    @Override
    public List<MemoryEntryView> archivalEntries() {
      return List.of();
    }
  }

  /** 确定性装配：HYBRID 档 = 引擎 + 直通索引（查表 embedder）；DELEGATED 档 = 直通。 */
  private MemoryServiceImpl deterministicService(LongTermMemoryStore store) {
    if (store.capabilities() == MemoryRecallCapability.DELEGATED) {
      return new MemoryServiceImpl(store);
    }
    List<MemoryVectorEntity> rows = new ArrayList<>();
    MemoryVectorRepository repo = statefulVectorRepo(rows);
    TextEmbedder embedder = lengthEmbedder();
    return new MemoryServiceImpl(
        store,
        new MemoryRecallEngine(repo, embedder, EQUAL, 20),
        new MemoryVectorIndex(repo, embedder, Runnable::run));
  }

  /** 内容长度导出的确定性 embedder（mock 语义，SC-004）。 */
  private static TextEmbedder lengthEmbedder() {
    return new TextEmbedder() {
      @Override
      public float[] embed(String text) {
        return new float[] {text.length(), 1};
      }

      @Override
      public String modelId() {
        return "mock/deterministic";
      }

      @Override
      public int dimensions() {
        return 2;
      }
    };
  }

  private static TextEmbedder brokenEmbedder() {
    return new TextEmbedder() {
      @Override
      public float[] embed(String text) {
        throw new IllegalStateException("embedding 服务不可达");
      }

      @Override
      public String modelId() {
        return "mock/broken";
      }

      @Override
      public int dimensions() {
        return 2;
      }
    };
  }

  private static MemoryVectorRepository emptyVectorRepo() {
    MemoryVectorRepository repo = mock(MemoryVectorRepository.class);
    when(repo.findByAgentName(anyString())).thenReturn(List.of());
    when(repo.findByAgentNameAndEntryHash(anyString(), anyString()))
        .thenReturn(java.util.Optional.empty());
    return repo;
  }

  private static MemoryVectorRepository statefulVectorRepo(List<MemoryVectorEntity> data) {
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
    return repo;
  }

  /** 背靠内存 List 的有状态 MemoryEntryRepository mock（同 MemoryStoreContractTest 形态）。 */
  private static MemoryEntryRepository fakeEntryRepo() {
    List<MemoryEntry> data = new ArrayList<>();
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
              if (e.getCreatedAt() == null) {
                try {
                  var field = MemoryEntry.class.getDeclaredField("createdAt");
                  field.setAccessible(true);
                  field.set(e, java.time.Instant.now());
                } catch (ReflectiveOperationException ex) {
                  throw new IllegalStateException(ex);
                }
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
    when(repo.findByAgentNameAndScopeOrderByIdDesc(anyString(), anyString(), any(Pageable.class)))
        .thenAnswer(
            inv -> {
              String agent = inv.getArgument(0);
              String scope = inv.getArgument(1);
              Pageable pageable = inv.getArgument(2);
              List<MemoryEntry> matched =
                  new ArrayList<>(
                      data.stream()
                          .filter(e -> e.getAgentName().equals(agent) && e.getScope().equals(scope))
                          .toList());
              matched.sort((a, b) -> Long.compare(b.getId(), a.getId()));
              return matched.stream().limit(pageable.getPageSize()).toList();
            });
    when(repo.searchArchival(anyString(), anyString()))
        .thenAnswer(
            inv -> {
              String agent = inv.getArgument(0);
              String needle = ((String) inv.getArgument(1)).replace("%", "");
              return data.stream()
                  .filter(
                      e ->
                          e.getAgentName().equals(agent)
                              && "ARCHIVAL".equals(e.getScope())
                              && e.getContent().toLowerCase(Locale.ROOT).contains(needle))
                  .toList();
            });
    return repo;
  }

  /** 编译占位：确保 InMemoryMemoryStore（mem0 旧替身）仍满足接口——真实 mem0 契约分支由 T024 挂。 */
  @Test
  void legacyInMemorySubstituteStillCompilesAgainstContract() {
    assertEquals(MemoryRecallCapability.DELEGATED, new InMemoryMemoryStore().capabilities());
  }
}
