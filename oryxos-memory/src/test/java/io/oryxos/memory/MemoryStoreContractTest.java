package io.oryxos.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.core.memory.MemoryScope;
import io.oryxos.storage.MemoryEntry;
import io.oryxos.storage.MemoryEntryRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.domain.Pageable;

/**
 * 课件《第22节》验收 harness：MemoryStoreContractTest——同一套断言对三档统一跑。
 *
 * <p>Mem0 档在契约集里用 {@link InMemoryMemoryStore} 替身（真实 REST 交互由 Mem0MemoryStoreTest 单独 mock）； SQLite
 * 档用背靠内存 List 的有状态 mock Repository，避免契约测试拉起 Spring 容器（真库 LIMIT/LIKE 由 SqliteMemoryStoreTest 与
 * MemoryEntryRepositoryTest 单独验证）。
 *
 * <p>PER_CLASS 生命周期：让 @MethodSource 工厂能非 static、从而访问实例注入的 @TempDir。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryStoreContractTest {

  @TempDir Path tempRoot;

  Stream<Arguments> allStores() {
    return Stream.of(
        Arguments.of(
            "markdown", (Supplier<LongTermMemoryStore>) () -> new MarkdownMemoryStore(tempRoot)),
        Arguments.of(
            "sqlite", (Supplier<LongTermMemoryStore>) () -> new SqliteMemoryStore(fakeRepo())),
        Arguments.of("mem0(替身)", (Supplier<LongTermMemoryStore>) InMemoryMemoryStore::new));
  }

  @ParameterizedTest(name = "[{0}]")
  @MethodSource("allStores")
  @DisplayName("截断只裁归档区_核心记忆一字不能少")
  void truncationKeepsCoreIntact(String name, Supplier<LongTermMemoryStore> factory) {
    LongTermMemoryStore memory = factory.get();
    memory.append("用户叫小王，偏好用 Java", MemoryScope.CORE);
    for (int i = 0; i < 500; i++) {
      memory.append("归档流水 " + i, MemoryScope.ARCHIVAL); // 灌到远超阈值
    }

    String loaded = memory.load();

    assertTrue(loaded.contains("用户叫小王，偏好用 Java"), name + ": 核心区完整——始终在场的底线");
    assertFalse(loaded.contains("归档流水 0"), name + ": 归档区最早的被裁掉");
    assertTrue(loaded.contains("归档流水 499"), name + ": 保留的是最近的");
  }

  @ParameterizedTest(name = "[{0}]")
  @MethodSource("allStores")
  @DisplayName("写入后立刻可读_不允许有缓存")
  void writeIsImmediatelyReadable_noCache(String name, Supplier<LongTermMemoryStore> factory) {
    LongTermMemoryStore memory = factory.get();
    memory.append("刚记的事", MemoryScope.ARCHIVAL);

    assertTrue(memory.load().contains("刚记的事"), name + ": 下一次 load 立即可见");
    assertFalse(memory.recallByKeyword("刚记的事").isEmpty(), name + ": 检索同样立即命中");
  }

  @ParameterizedTest(name = "[{0}]")
  @MethodSource("allStores")
  @DisplayName("scope 路由到正确区块")
  void scopeRoutesToCorrectSection(String name, Supplier<LongTermMemoryStore> factory) {
    LongTermMemoryStore memory = factory.get();
    memory.append("核心内容 alpha", MemoryScope.CORE);
    memory.append("归档内容 beta", MemoryScope.ARCHIVAL);

    assertTrue(memory.recallByKeyword("alpha").isEmpty(), name + ": 核心区不参与检索");
    assertFalse(memory.recallByKeyword("beta").isEmpty(), name + ": 归档区可检索");
    assertTrue(memory.load().contains("核心内容 alpha") && memory.load().contains("归档内容 beta"));
  }

  @ParameterizedTest(name = "[{0}]")
  @MethodSource("allStores")
  @DisplayName("recall 只搜归档区")
  void recallSearchesArchivalOnly(String name, Supplier<LongTermMemoryStore> factory) {
    LongTermMemoryStore memory = factory.get();
    memory.append("秘密关键词 zzz 在核心区", MemoryScope.CORE);

    assertTrue(memory.recallByKeyword("zzz").isEmpty(), name + ": 核心区的词检索不到");
  }

  /** 背靠内存 List 的有状态 mock：只 stub SqliteMemoryStore 用到的四个方法。 */
  private static MemoryEntryRepository fakeRepo() {
    List<MemoryEntry> data = new ArrayList<>();
    long[] seq = {0};
    MemoryEntryRepository repo = mock(MemoryEntryRepository.class);
    when(repo.save(any()))
        .thenAnswer(
            inv -> {
              MemoryEntry e = inv.getArgument(0);
              assignId(e, ++seq[0]);
              data.add(e);
              return e;
            });
    when(repo.findByScopeOrderByIdAsc(anyString()))
        .thenAnswer(
            inv -> {
              String scope = inv.getArgument(0);
              return data.stream().filter(e -> e.getScope().equals(scope)).toList();
            });
    when(repo.findByScopeOrderByIdDesc(anyString(), any(Pageable.class)))
        .thenAnswer(
            inv -> {
              String scope = inv.getArgument(0);
              Pageable pageable = inv.getArgument(1);
              List<MemoryEntry> matched =
                  new ArrayList<>(data.stream().filter(e -> e.getScope().equals(scope)).toList());
              matched.sort((a, b) -> Long.compare(b.getId(), a.getId()));
              return matched.stream().limit(pageable.getPageSize()).toList();
            });
    when(repo.searchArchival(anyString()))
        .thenAnswer(
            inv -> {
              String needle = ((String) inv.getArgument(0)).replace("%", "");
              return data.stream()
                  .filter(e -> "ARCHIVAL".equals(e.getScope()) && e.getContent().contains(needle))
                  .toList();
            });
    return repo;
  }

  private static void assignId(MemoryEntry entry, long id) {
    try {
      var field = MemoryEntry.class.getDeclaredField("id");
      field.setAccessible(true);
      field.set(entry, id);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("测试无法设置 MemoryEntry.id", e);
    }
  }
}
