package io.oryxos.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.memory.MemoryScope;
import io.oryxos.core.memory.MemoryService;
import io.oryxos.core.session.Session;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/** MemoryServiceImpl 门面：buildContext 返回长期记忆（核心全量+归档截断），remember/recall 转发。 */
class MemoryServiceImplTest {

  private MemoryService service() {
    return new MemoryServiceImpl(new InMemoryMemoryStore());
  }

  @Test
  @DisplayName("buildContext返回长期记忆_核心记忆完整在内")
  void buildContextReturnsLongTermMemoryWithCoreIntact() {
    MemoryService service = service();
    service.remember("用户叫小王", MemoryScope.CORE);
    service.remember("归档一条", MemoryScope.ARCHIVAL);

    String context = service.buildContext(new Session("cli:wang:default", "default"));

    assertTrue(context.contains("用户叫小王"), "核心记忆完整在内");
    assertTrue(context.contains("归档一条"), "归档截断后的部分也在");
  }

  @Test
  @DisplayName("remember/recall 转发给底层 store")
  void rememberAndRecallDelegateToStore() {
    MemoryService service = service();
    service.remember("项目叫 OryxOS", MemoryScope.ARCHIVAL);

    assertFalse(service.recall("OryxOS").isEmpty());
    assertTrue(service.recall("不存在的词").isEmpty());
  }

  @Test
  @DisplayName("readAll 返回长期记忆全文（委托 store.load）")
  void readAllReturnsFullMemory() {
    MemoryService service = service();
    service.remember("核心偏好", MemoryScope.CORE);
    service.remember("归档条目", MemoryScope.ARCHIVAL);

    String all = service.readAll("default");

    assertTrue(all.contains("核心偏好"));
    assertTrue(all.contains("归档条目"));
  }

  @TempDir Path root;

  @Test
  @DisplayName("DELEGATED 档 recall 直通 store_不走引擎（FR-009 路由）")
  void delegatedStoreBypassesEngine() {
    MemoryRecallEngine engine = mock(MemoryRecallEngine.class);
    MemoryServiceImpl service =
        new MemoryServiceImpl(new InMemoryMemoryStore(), engine, mock(MemoryVectorIndex.class));
    service.remember("mem0 里的条目", MemoryScope.ARCHIVAL);

    List<String> lines = service.recall("条目");

    assertFalse(lines.isEmpty(), "直通 store.recallByKeyword");
    verify(engine, never()).recall(any(), anyString(), anyString());
  }

  @Test
  @DisplayName("HYBRID 档 recall 走三路引擎")
  void hybridStoreRoutesThroughEngine() {
    MemoryRecallEngine engine = mock(MemoryRecallEngine.class);
    when(engine.recall(any(), anyString(), anyString())).thenReturn(List.of("引擎融合结果"));
    MemoryServiceImpl service = new MemoryServiceImpl(new MarkdownMemoryStore(root), engine, null);

    assertEquals(List.of("引擎融合结果"), service.recall("关键词"));
  }

  @Test
  @DisplayName("remember 仅 archival 入队索引_core 不入（FR-005）")
  void rememberEnqueuesOnlyArchivalEntries() {
    MemoryVectorIndex index = mock(MemoryVectorIndex.class);
    MemoryServiceImpl service = new MemoryServiceImpl(new MarkdownMemoryStore(root), null, index);

    service.remember("核心事实", MemoryScope.CORE);
    verify(index, never()).enqueue(anyString(), any());

    service.remember("归档事件", MemoryScope.ARCHIVAL);
    ArgumentCaptor<io.oryxos.core.memory.MemoryEntryView> entry =
        ArgumentCaptor.forClass(io.oryxos.core.memory.MemoryEntryView.class);
    verify(index).enqueue(anyString(), entry.capture());
    assertTrue(entry.getValue().content().contains("归档事件"), "入队的是刚写入的归档条目原文行");
  }

  @Test
  @DisplayName("并发 remember 各自入队自己的条目（不依赖 getLast）")
  void concurrentRememberEnqueuesOwnEntries() throws Exception {
    MemoryVectorIndex index = mock(MemoryVectorIndex.class);
    MemoryServiceImpl service = new MemoryServiceImpl(new MarkdownMemoryStore(root), null, index);
    int n = 20;
    java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(n);
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(8);
    try {
      for (int i = 0; i < n; i++) {
        final String marker = "concurrent-entry-" + i;
        pool.execute(
            () -> {
              try {
                start.await();
                service.remember(marker, MemoryScope.ARCHIVAL);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                done.countDown();
              }
            });
      }
      start.countDown();
      assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
    ArgumentCaptor<io.oryxos.core.memory.MemoryEntryView> entry =
        ArgumentCaptor.forClass(io.oryxos.core.memory.MemoryEntryView.class);
    verify(index, org.mockito.Mockito.times(n)).enqueue(anyString(), entry.capture());
    java.util.Set<String> markers = new java.util.HashSet<>();
    for (io.oryxos.core.memory.MemoryEntryView view : entry.getAllValues()) {
      for (int i = 0; i < n; i++) {
        if (view.content().contains("concurrent-entry-" + i)) {
          markers.add("concurrent-entry-" + i);
        }
      }
    }
    assertEquals(n, markers.size(), "每个并发写入都应入队自己的条目，不得互相覆盖 getLast");
  }

  @Test
  @DisplayName("reconcileIndex 委托索引对账_DELEGATED 档为 no-op")
  void reconcileIndexDelegatesAndSkipsDelegated() {
    MemoryVectorIndex index = mock(MemoryVectorIndex.class);
    MemoryServiceImpl hybrid = new MemoryServiceImpl(new MarkdownMemoryStore(root), null, index);
    hybrid.remember("归档条目", MemoryScope.ARCHIVAL);
    hybrid.reconcileIndex("default");
    verify(index, org.mockito.Mockito.atLeastOnce()).reconcile(anyString(), any());

    MemoryVectorIndex untouched = mock(MemoryVectorIndex.class);
    new MemoryServiceImpl(new InMemoryMemoryStore(), null, untouched).reconcileIndex("default");
    verify(untouched, never()).reconcile(anyString(), any());
  }
}
