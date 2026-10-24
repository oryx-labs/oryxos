package io.oryxos.knowledge.watch;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.oryxos.knowledge.index.KnowledgeIndexService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** KnowledgeBaseReconciler 单元：启动全库对账、单库过滤（非法清单/远程后端）、删除清理与失败隔离。 */
class KnowledgeBaseReconcilerTest {

  private KnowledgeIndexService indexService;
  private KnowledgeBaseReconciler reconciler;

  @BeforeEach
  void setUp() {
    indexService = mock(KnowledgeIndexService.class);
    reconciler = new KnowledgeBaseReconciler(indexService);
  }

  @Test
  @DisplayName("目录不存在时全库对账空转")
  void reconcileAll_skipsWhenDirectoryMissing(@TempDir Path tmp) {
    reconciler.reconcileAll(tmp.resolve("missing"));
    verifyNoInteractions(indexService);
  }

  @Test
  @DisplayName("全库对账逐个目录按启动口径对账")
  void reconcileAll_reconcilesEveryLocalBase(@TempDir Path tmp) throws IOException {
    Path root = Files.createDirectory(tmp.resolve("knowledge"));
    localBase(root.resolve("kb1"), "kb1");
    localBase(root.resolve("kb2"), "kb2");

    reconciler.reconcileAll(root);

    verify(indexService).reconcile("kb1", true);
    verify(indexService).reconcile("kb2", true);
  }

  @Test
  @DisplayName("非法清单的库跳过，其余库照常对账")
  void reconcileAll_isolatesInvalidManifest(@TempDir Path tmp) throws IOException {
    Path root = Files.createDirectory(tmp.resolve("knowledge"));
    localBase(root.resolve("good"), "good");
    Path broken = Files.createDirectory(root.resolve("broken"));
    Files.writeString(broken.resolve("KNOWLEDGE.md"), "no frontmatter");

    reconciler.reconcileAll(root);

    verify(indexService).reconcile("good", true);
    verify(indexService, never()).reconcile("broken", true);
  }

  @Test
  @DisplayName("远程后端库不做本地对账")
  void reconcile_skipsRemoteBackend(@TempDir Path tmp) throws IOException {
    Path base = Files.createDirectory(tmp.resolve("remote-kb"));
    Files.writeString(
        base.resolve("KNOWLEDGE.md"),
        "---\nname: remote-kb\ndescription: 远程库\nbackend: ragflow\n---\n");

    reconciler.reconcile(base, true);

    verifyNoInteractions(indexService);
  }

  @Test
  @DisplayName("单库对账把 afterRestart 原样传给索引层")
  void reconcile_forwardsAfterRestartFlag(@TempDir Path tmp) throws IOException {
    Path base = localBase(tmp.resolve("kb"), "kb");

    reconciler.reconcile(base, false);

    verify(indexService).reconcile("kb", false);
  }

  @Test
  @DisplayName("索引失败不拖垮调用方")
  void reconcile_swallowsIndexFailure(@TempDir Path tmp) throws IOException {
    Path base = localBase(tmp.resolve("kb"), "kb");
    doThrow(new IllegalStateException("boom")).when(indexService).reconcile("kb", false);

    assertDoesNotThrow(() -> reconciler.reconcile(base, false));
  }

  @Test
  @DisplayName("非目录路径单库对账空转")
  void reconcile_skipsNonDirectory(@TempDir Path tmp) throws IOException {
    Path file = Files.writeString(tmp.resolve("not-a-dir"), "x");

    reconciler.reconcile(file, true);

    verifyNoInteractions(indexService);
  }

  @Test
  @DisplayName("删除库时清理索引；清理失败不拖垮调用方")
  void removeBase_deletesIndexAndSwallowsFailure(@TempDir Path tmp) {
    Path base = tmp.resolve("kb");

    reconciler.removeBase(base);
    verify(indexService).deleteBase("kb");

    doThrow(new IllegalStateException("boom")).when(indexService).deleteBase(anyString());
    assertDoesNotThrow(() -> reconciler.removeBase(base));
  }

  private static Path localBase(Path base, String name) throws IOException {
    Files.createDirectories(base);
    Files.writeString(
        base.resolve("KNOWLEDGE.md"),
        "---\nname: " + name + "\ndescription: 本地库\nbackend: local\n---\n");
    return base;
  }
}
