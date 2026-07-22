package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.oryxos.core.skill.AgentSkillCoordinator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 第30节：AgentStore——写 / 回滚删 / 归档，限定 .oryxos/ 内。 */
class AgentStoreTest {

  @TempDir Path oryxosRoot;
  private AgentStore store;

  @BeforeEach
  void setUp() {
    store = new AgentStore(oryxosRoot);
  }

  @Test
  @DisplayName("write 写出 AGENT.md、可读回")
  void write_createsAgentMarkdown() throws IOException {
    Path dir = store.write("demo", "---\nname: demo\n---\n正文");

    assertEquals(oryxosRoot.resolve("agents").resolve("demo"), dir);
    assertTrue(Files.isRegularFile(dir.resolve("AGENT.md")));
    assertTrue(Files.readString(dir.resolve("AGENT.md")).contains("name: demo"));
  }

  @Test
  @DisplayName("delete 回滚删除整个目录")
  void delete_removesDirectory() {
    Path dir = store.write("demo", "x");

    store.delete(dir);

    assertFalse(Files.exists(dir));
  }

  @Test
  @DisplayName("delete 对 null 与非 Agent 直属路径给出稳定参数错误")
  void delete_rejectsPathsOutsideDirectAgentChildren() {
    assertThrows(IllegalArgumentException.class, () -> store.delete(null));
    assertThrows(IllegalArgumentException.class, () -> store.delete(oryxosRoot));
  }

  @Test
  @DisplayName("archive 把目录移入 archive/、原目录消失、不物理删")
  void archive_movesToArchiveNotPhysicalDelete() {
    store.write("demo", "x");

    store.archive("demo");

    assertFalse(Files.exists(oryxosRoot.resolve("agents").resolve("demo")), "原目录已移走");
    assertTrue(Files.exists(oryxosRoot.resolve("archive").resolve("demo")), "归档区保留，可追溯");
  }

  @Test
  @DisplayName("非法 name 拒绝（防路径穿越）")
  void write_unsafeName_rejected() {
    assertThrows(IllegalArgumentException.class, () -> store.write("../evil", "x"));
  }

  @Test
  @DisplayName("大小写不敏感文件系统上 Agent 别名不能覆写真实目录")
  void write_caseAliasCannotOverwriteExistingAgentWhenFilesystemAliasesIt() throws IOException {
    Path actual = store.write("Ops", "original");
    Path alias = oryxosRoot.resolve("agents/ops");
    Assumptions.assumeTrue(Files.exists(alias) && Files.isSameFile(actual, alias));

    assertThrows(IllegalArgumentException.class, () -> store.write("ops", "tampered"));

    assertEquals("original", Files.readString(actual.resolve("AGENT.md")));
  }

  @Test
  @DisplayName("createAll 对大小写别名失败且绝不删除或改写原 Agent")
  void createAll_caseAliasPreservesTheCompleteExistingAgent() throws IOException {
    Map<String, String> original =
        Map.of("AGENT.md", "original", "skills/weather/SKILL.md", "private-skill");
    Path actual = store.createAll("Ops", original);

    assertThrows(
        IllegalArgumentException.class,
        () -> store.createAll("ops", Map.of("AGENT.md", "tampered")));

    assertEquals("original", Files.readString(actual.resolve("AGENT.md")));
    assertEquals("private-skill", Files.readString(actual.resolve("skills/weather/SKILL.md")));
  }

  @Test
  @DisplayName("writeAll 在写入前校验全部路径，坏路径不会先覆写旧文件")
  void writeAll_invalidLaterPath_preservesExistingFiles() throws IOException {
    Path dir = store.write("demo", "old");
    Map<String, String> files = new LinkedHashMap<>();
    files.put("AGENT.md", "new");
    files.put("../outside.txt", "bad");

    assertThrows(IllegalArgumentException.class, () -> store.writeAll("demo", files));

    assertEquals("old", Files.readString(dir.resolve("AGENT.md")));
  }

  @Test
  @DisplayName("writeAll 在 staging 前拒绝 AGENT.md 的大小写别名且不覆写旧文件")
  void writeAll_caseFoldedAliases_preserveExistingAgentMarkdown() throws IOException {
    Path dir = store.write("demo", "old");
    Map<String, String> files = new LinkedHashMap<>();
    files.put("AGENT.md", "validated");
    files.put("agent.md", "must-not-win");

    assertThrows(IllegalArgumentException.class, () -> store.writeAll("demo", files));

    assertEquals("old", Files.readString(dir.resolve("AGENT.md")));
    try (var children = Files.list(dir)) {
      assertEquals(
          List.of("AGENT.md"), children.map(path -> path.getFileName().toString()).toList());
    }
  }

  @Test
  @DisplayName("writeAll 在 staging 前拒绝 NFC 等价路径且不创建父目录")
  void writeAll_nfcEquivalentAliases_createNoFiles() throws IOException {
    Path dir = store.write("demo", "old");
    Map<String, String> files = new LinkedHashMap<>();
    files.put("AGENT.md", "new");
    files.put("references/caf\u00e9.md", "composed");
    files.put("references/cafe\u0301.md", "decomposed");

    assertThrows(IllegalArgumentException.class, () -> store.writeAll("demo", files));

    assertEquals("old", Files.readString(dir.resolve("AGENT.md")));
    assertFalse(Files.exists(dir.resolve("references")));
  }

  @Test
  @DisplayName("writeAll 在加锁和落盘前拒绝 Skill 管理 sidecar")
  void writeAll_reservedSkillSidecar_rejectedBeforeMutation() throws IOException {
    Path dir = store.write("demo", "old");
    AgentSkillCoordinator coordinator = mock(AgentSkillCoordinator.class);
    AgentStore coordinated = new AgentStore(oryxosRoot, coordinator);
    Map<String, String> files = new LinkedHashMap<>();
    files.put("AGENT.md", "new");
    files.put("skills/weather/.oryxos-disabled", "");

    assertThrows(IllegalArgumentException.class, () -> coordinated.writeAll("demo", files));

    verifyNoInteractions(coordinator);
    assertEquals("old", Files.readString(dir.resolve("AGENT.md")));
    assertFalse(Files.exists(dir.resolve("skills/weather/.oryxos-disabled")));
  }

  @Test
  @DisplayName("writeFile 拒绝 Skill 包内所有 .oryxos-* 控制成员")
  void writeFile_reservedSkillMembers_rejected() {
    Path dir = store.write("demo", "old");
    List<String> reservedPaths =
        List.of(
            "skills/weather/.oryxos-disabled",
            "skills/weather/.oryxos-origin.yml",
            "skills/weather/references/.oryxos-private",
            "Skills/weather/.ORYXOS-control");

    for (String path : reservedPaths) {
      assertThrows(IllegalArgumentException.class, () -> store.writeFile("demo", path, "bad"));
    }

    assertFalse(Files.exists(dir.resolve("skills")));
    assertFalse(Files.exists(dir.resolve("Skills")));
  }

  @Test
  @DisplayName("writeAll 第二次目录移动失败时完整恢复旧批次且不留下新文件")
  void writeAll_secondMoveFailure_rollsBackWholeBatch() throws IOException {
    Map<String, String> oldFiles = new LinkedHashMap<>();
    oldFiles.put("AGENT.md", "old-agent");
    oldFiles.put("scripts/keep.sh", "old-script");
    Path dir = store.writeAll("demo", oldFiles);
    AtomicInteger moves = new AtomicInteger();
    AtomicBoolean backupSharedParent = new AtomicBoolean();
    AgentStore failing =
        new AgentStore(
            oryxosRoot,
            null,
            (source, target) -> {
              int move = moves.incrementAndGet();
              if (move == 1) {
                try (var siblings = Files.list(target.getParent())) {
                  backupSharedParent.set(
                      siblings.anyMatch(
                          path ->
                              String.valueOf(path.getFileName()).startsWith(".oryxos-backup-")));
                }
              }
              if (move == 2) {
                throw new IOException("second move failed");
              }
              Files.move(
                  source,
                  target,
                  StandardCopyOption.ATOMIC_MOVE,
                  StandardCopyOption.REPLACE_EXISTING);
            });
    Map<String, String> newFiles = new LinkedHashMap<>();
    newFiles.put("AGENT.md", "new-agent");
    newFiles.put("references/new.md", "partial");

    assertThrows(UncheckedIOException.class, () -> failing.writeAll("demo", newFiles));

    assertEquals(2, moves.get());
    assertTrue(
        backupSharedParent.get(), "rollback backup must share the target directory/FileStore");
    assertEquals("old-agent", Files.readString(dir.resolve("AGENT.md")));
    assertEquals("old-script", Files.readString(dir.resolve("scripts/keep.sh")));
    assertFalse(Files.exists(dir.resolve("references/new.md")));
    try (var entries = Files.list(oryxosRoot.resolve("agents"))) {
      assertEquals(List.of("demo"), entries.map(path -> path.getFileName().toString()).toList());
    }
    try (var entries = Files.walk(oryxosRoot)) {
      assertFalse(
          entries.anyMatch(
              path -> {
                String filename = String.valueOf(path.getFileName());
                return filename.startsWith(".oryxos-write-")
                    || filename.startsWith(".oryxos-agent-stage-")
                    || filename.startsWith(".oryxos-backup-");
              }));
    }
  }

  @Test
  @DisplayName("原子替换成功后只留下目标文件、不泄露同目录临时文件")
  void write_atomicallyReplacesTargetAndCleansTempFile() throws IOException {
    Path dir = store.write("demo", "old");

    store.write("demo", "new");

    assertEquals("new", Files.readString(dir.resolve("AGENT.md")));
    try (var entries = Files.list(dir)) {
      assertEquals(List.of("AGENT.md"), entries.map(p -> p.getFileName().toString()).toList());
    }
  }

  @Test
  @DisplayName("原子移动失败时旧目标保持不变且临时文件被清理")
  void write_atomicMoveFailure_preservesOldTargetAndCleansTemp() throws IOException {
    Path dir = store.write("demo", "old");
    AgentStore failing =
        new AgentStore(
            oryxosRoot,
            null,
            (source, target) -> {
              throw new IOException("atomic move unavailable");
            });

    assertThrows(UncheckedIOException.class, () -> failing.write("demo", "new"));

    assertEquals("old", Files.readString(dir.resolve("AGENT.md")));
    try (var entries = Files.list(dir)) {
      assertEquals(List.of("AGENT.md"), entries.map(p -> p.getFileName().toString()).toList());
    }
  }

  @Test
  @DisplayName("受管 Skill 路径含 symlink 时拒绝且不写到工作区外")
  void writeFile_symlinkAncestor_rejected() throws IOException {
    Path dir = store.write("demo", "x");
    Path outside = Files.createDirectories(oryxosRoot.resolve("outside"));
    Path skills = Files.createDirectories(dir.resolve("skills"));
    Files.createSymbolicLink(skills.resolve("weather"), outside);

    assertThrows(
        IllegalArgumentException.class,
        () -> store.writeFile("demo", "skills/weather/nested/SKILL.md", "escaped"));

    assertFalse(Files.exists(outside.resolve("SKILL.md")));
    assertFalse(Files.exists(outside.resolve("nested")), "拒绝前不能先在链接目标中创建父目录");
  }

  @Test
  @DisplayName("生产构造器把 writeAll 接入 AgentSkillCoordinator 写锁")
  void writeAll_withCoordinator_routesThroughMutation() {
    AgentSkillCoordinator coordinator = mock(AgentSkillCoordinator.class);
    AgentStore coordinated = new AgentStore(oryxosRoot, coordinator);

    coordinated.writeAll("demo", Map.of("AGENT.md", "x"));

    verify(coordinator).mutate(eq("demo"), any());
  }
}
