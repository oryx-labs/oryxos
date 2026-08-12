package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
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
  @DisplayName("archive 把目录移入 archive/、原目录消失、不物理删")
  void archive_movesToArchiveNotPhysicalDelete() {
    store.write("demo", "x");

    store.archive("demo");

    assertFalse(Files.exists(oryxosRoot.resolve("agents").resolve("demo")), "原目录已移走");
    assertTrue(Files.exists(oryxosRoot.resolve("archive").resolve("demo")), "归档区保留，可追溯");
  }

  @Test
  @DisplayName("archive 时间戳目标已存在时追加序号且不覆盖")
  void archive_collisionUsesUniqueSuffix() throws IOException {
    long millis = 1_700_000_000_000L;
    AgentStore fixed =
        new AgentStore(oryxosRoot, Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC));
    Files.createDirectories(oryxosRoot.resolve("archive/demo"));
    Files.createDirectories(oryxosRoot.resolve("archive/demo-" + millis));
    fixed.write("demo", "x");

    fixed.archive("demo");

    assertTrue(Files.exists(oryxosRoot.resolve("archive/demo-" + millis + "-2/AGENT.md")));
  }

  @Test
  @DisplayName("非法 name 拒绝（防路径穿越）")
  void write_unsafeName_rejected() {
    assertThrows(IllegalArgumentException.class, () -> store.write("../evil", "x"));
  }

  @Test
  @DisplayName("write/writeAll 拒绝经父链接逃逸及占用 skills 保留命名空间")
  void writesRejectSymlinkEscapeAndReservedSkills() throws IOException {
    Path outside = Files.createDirectories(oryxosRoot.resolveSibling("agent-store-outside"));
    Path agent = Files.createDirectories(oryxosRoot.resolve("agents/demo"));
    Files.createSymbolicLink(agent.resolve("escape"), outside);

    assertThrows(
        IllegalArgumentException.class,
        () -> store.writeAll("demo", Map.of("escape/pwned.txt", "bad")));
    assertFalse(Files.exists(outside.resolve("pwned.txt")));
    Path outsideFile = Files.writeString(outside.resolve("keep.txt"), "keep");
    Files.createSymbolicLink(agent.resolve("final.txt"), outsideFile);
    assertThrows(
        IllegalArgumentException.class, () -> store.writeAll("demo", Map.of("final.txt", "bad")));
    assertEquals("keep", Files.readString(outsideFile));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.writeAll("demo", Map.of("skills/report/SKILL.md", "copy")));
    store.writeAll("demo", Map.of("scripts/ok.py", "ok"));
    assertEquals("ok", Files.readString(agent.resolve("scripts/ok.py")));
  }

  @Test
  @DisplayName("writeAll 全量预校验失败时已存在文件保持原样")
  void writeAllValidationFailureIsAtomic() throws IOException {
    Path agent = store.write("demo", "old");
    Files.createDirectories(agent.resolve("collision"));
    Map<String, String> files = new LinkedHashMap<>();
    files.put("AGENT.md", "new");
    files.put("collision", "not-a-file");

    assertThrows(IllegalArgumentException.class, () -> store.writeAll("demo", files));

    assertEquals("old", Files.readString(agent.resolve("AGENT.md")));
    assertTrue(Files.isDirectory(agent.resolve("collision")));
  }
}
