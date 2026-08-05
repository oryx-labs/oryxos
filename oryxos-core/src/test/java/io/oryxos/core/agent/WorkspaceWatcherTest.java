package io.oryxos.core.agent;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.oryxos.core.profile.ProfileRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchService;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** WorkspaceWatcher harness：丢目录即上线、删目录注销、坏目录不拖垮；issue #61 递归监听 + 刷新不残留旧定时。 */
class WorkspaceWatcherTest {

  @TempDir Path oryxosRoot;

  private Path agentsDir;
  private ProfileRegistry registry;
  private AgentScheduler scheduler;
  private WorkspaceWatcher watcher;

  @BeforeEach
  void setUp() throws IOException {
    agentsDir = oryxosRoot.resolve("agents");
    Files.createDirectories(agentsDir);
    registry = new ProfileRegistry();
    scheduler = mock(AgentScheduler.class);
    AgentLoader loader = new AgentLoader(agentsDir, Set.of("deepseek"));
    AgentLifecycleService lifecycle =
        new AgentLifecycleService(
            loader,
            registry,
            scheduler,
            new AgentStore(oryxosRoot),
            mock(io.oryxos.core.provider.ProviderService.class),
            "deepseek",
            "deepseek",
            "deepseek-chat",
            java.util.Map.of(),
            mock(io.oryxos.core.notify.NotifyChannelRegistry.class));
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

  @Test
  @DisplayName("issue #61：重复编辑（refresh）先注销旧定时，再注册——旧 cron 不残留")
  void handleChange_repeatedEdit_cancelsPreviousSchedulesBeforeReregister() throws IOException {
    Path dir = writeAgent("demo", "name: demo\nprovider:\n  name: deepseek\n  model: m");

    watcher.handleChange(dir, ENTRY_CREATE); // 首次登记：无旧 Profile，不应注销
    verify(scheduler, never()).unregisterProfile(any());

    watcher.handleChange(dir, ENTRY_MODIFY); // 再次编辑：必须先注销旧的，避免句柄泄漏 + 双跑
    verify(scheduler, times(1)).unregisterProfile(any());
  }

  @Test
  @DisplayName("issue #61：子目录里 AGENT.md 新增/删除也被捕获（不再只盯根目录）")
  void dispatch_agentFileInsideSubdir_registersThenUnregisters() throws IOException {
    Path dir = writeAgent("demo", "name: demo\nprovider:\n  name: deepseek\n  model: m");
    Path agentFile = dir.resolve("AGENT.md");
    try (WatchService ws = agentsDir.getFileSystem().newWatchService()) {
      watcher.dispatch(ws, dir, agentFile, ENTRY_CREATE);
      assertTrue(registry.exists("demo"), "子目录内 AGENT.md 出现即上线");

      watcher.dispatch(ws, dir, agentFile, ENTRY_DELETE);
      assertFalse(registry.exists("demo"), "AGENT.md 被删即注销");
    }
  }

  @Test
  @DisplayName("issue #61：新建 Agent 目录（AGENT.md 已就位）在根事件中即登记并补挂监听")
  void dispatch_newAgentDirAtRoot_registersAndWatches() throws IOException {
    Path dir = writeAgent("demo", "name: demo\nprovider:\n  name: deepseek\n  model: m");
    try (WatchService ws = agentsDir.getFileSystem().newWatchService()) {
      watcher.dispatch(ws, agentsDir, dir, ENTRY_CREATE);
      assertTrue(registry.exists("demo"), "丢一整个 Agent 目录进来即上线");

      watcher.dispatch(ws, agentsDir, dir, ENTRY_DELETE);
      assertFalse(registry.exists("demo"), "根事件里目录被删即注销");
    }
  }
}
