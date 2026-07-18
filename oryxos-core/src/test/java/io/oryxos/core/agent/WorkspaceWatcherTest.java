package io.oryxos.core.agent;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.oryxos.core.profile.ProfileRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 课件《第30节》验收 harness：WorkspaceWatcherTest——丢目录即上线、删目录注销、坏目录不拖垮。 */
class WorkspaceWatcherTest {

  @TempDir Path oryxosRoot;

  private Path agentsDir;
  private ProfileRegistry registry;
  private WorkspaceWatcher watcher;

  @BeforeEach
  void setUp() throws IOException {
    agentsDir = oryxosRoot.resolve("agents");
    Files.createDirectories(agentsDir);
    registry = new ProfileRegistry();
    AgentLoader loader = new AgentLoader(agentsDir, Set.of("deepseek"));
    AgentLifecycleService lifecycle =
        new AgentLifecycleService(
            loader,
            registry,
            mock(AgentScheduler.class),
            new AgentStore(oryxosRoot),
            mock(io.oryxos.core.provider.ProviderService.class),
            "deepseek",
            "deepseek",
            "deepseek-chat");
    watcher = new WorkspaceWatcher(lifecycle, oryxosRoot, Runnable::run);
  }

  private Path writeAgent(String name, String frontmatter) throws IOException {
    Path dir = Files.createDirectories(agentsDir.resolve(name));
    Files.writeString(dir.resolve("AGENT.md"), "---\n" + frontmatter + "\n---\n正文");
    return dir;
  }

  @Test
  @DisplayName("手工丢一个 Agent 目录 → 监听事件触发 register、免重启出现在注册表")
  void handleChange_create_registersAgent() throws IOException {
    Path dir = writeAgent("demo", "name: demo\nprovider:\n  name: deepseek\n  model: m");

    watcher.handleChange(dir, ENTRY_CREATE);

    assertTrue(registry.exists("demo"), "丢目录即上线：Agent 免重启出现在 ProfileRegistry");
  }

  @Test
  @DisplayName("手工删目录 → 监听事件触发注销")
  void handleChange_delete_unregisters() throws IOException {
    Path dir = writeAgent("demo", "name: demo\nprovider:\n  name: deepseek\n  model: m");
    watcher.handleChange(dir, ENTRY_CREATE);
    assertTrue(registry.exists("demo"));

    watcher.handleChange(dir, ENTRY_DELETE);

    assertFalse(registry.exists("demo"), "删目录即注销");
  }

  @Test
  @DisplayName("单个坏目录不拖垮监听（记日志跳过、不抛）")
  void handleChange_badDir_isSkipped_watcherSurvives() throws IOException {
    Path bad = writeAgent("bad", "provider:\n  name: deepseek\n  model: m"); // 缺 name

    assertDoesNotThrow(() -> watcher.handleChange(bad, ENTRY_CREATE));
    assertFalse(registry.exists("bad"), "坏目录未登记");
  }
}
