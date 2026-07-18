package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
  @DisplayName("非法 name 拒绝（防路径穿越）")
  void write_unsafeName_rejected() {
    assertThrows(IllegalArgumentException.class, () -> store.write("../evil", "x"));
  }
}
