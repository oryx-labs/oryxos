package io.oryxos.core.agent;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// 第二条录入路径（第 30 节）：实时监听 .oryxos/agents/，任何 Agent 目录新增/改/删都汇到与
// API 上传同一段 AgentLifecycleService（register / refresh / 注销）。启动全量扫描由装配层既有链路
// （AgentLoader.loadAll + AgentScheduler.registerAll）完成，本类只管启动之后的实时变更。
//
// issue #61：JDK WatchService 注册不递归——只盯根目录会漏掉 agents/<agent>/AGENT.md 的增/改/删，
// 于是新建或编辑器改动的 Agent 直到重启才生效、定时变更运行时不落地。故本类维护
// WatchKey → 被监听目录 的映射：根目录盯直接子目录的增删，每个 Agent 子目录各自盯自己的
// AGENT.md；新目录一出现即补挂监听，被删即注销并撤挂。
//
// 基础设施守护线程（与 25 节 AgentScheduler 同类），不把异步编程模型引进请求链路（不违反宪法七）。
/** 实时监听 {@code .oryxos/agents/}，把 Agent 目录变更汇到 {@link AgentLifecycleService}。 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "lifecycle/executor 是 Spring 注入的共享单例，构造注入共享同一引用正是意图（无法也不应防御性拷贝）。")
public class WorkspaceWatcher {

  private static final Logger LOG = LoggerFactory.getLogger(WorkspaceWatcher.class);

  /** Agent 定义文件名——只有它的增/改/删才牵动该 Agent 的注册/注销。 */
  private static final String AGENT_FILE = "AGENT.md";

  private final AgentLifecycleService lifecycle;
  private final Path agentsDir;
  private final Executor watcherExecutor;

  /** {@link WatchKey} → 被监听目录（根目录或某个 Agent 子目录），把事件解析回来源目录。 */
  private final Map<WatchKey, Path> watchedDirs = new ConcurrentHashMap<>();

  public WorkspaceWatcher(
      AgentLifecycleService lifecycle, Path oryxosRoot, Executor watcherExecutor) {
    this.lifecycle = lifecycle;
    this.agentsDir = oryxosRoot.resolve("agents");
    this.watcherExecutor = watcherExecutor;
  }

  /** 装配层 {@code @Bean(initMethod="start")} 调用：在守护线程执行器上跑监听循环。 */
  public void start() {
    WatchService watchService;
    try {
      Files.createDirectories(agentsDir);
      watchService = agentsDir.getFileSystem().newWatchService();
      registerDir(watchService, agentsDir); // 根目录：盯直接子 Agent 目录的增删
      // WatchService 不递归：已存在的每个 Agent 子目录必须各自挂，才能收到其 AGENT.md 事件
      try (DirectoryStream<Path> children =
          Files.newDirectoryStream(agentsDir, Files::isDirectory)) {
        for (Path child : children) {
          registerDir(watchService, child);
        }
      }
    } catch (IOException e) {
      LOG.warn("WorkspaceWatcher 启动失败，实时监听不可用: {}", sanitize(e.getMessage()));
      return;
    }
    watcherExecutor.execute(() -> loop(watchService));
  }

  /** 把目录注册进 WatchService 并记入映射；同一目录重复注册返回同一 {@link WatchKey}（幂等）。 */
  private void registerDir(WatchService watchService, Path dir) throws IOException {
    WatchKey key = dir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
    watchedDirs.put(key, dir);
  }

  private void loop(WatchService watchService) {
    while (true) {
      WatchKey key;
      try {
        key = watchService.take();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      Path dir = watchedDirs.get(key);
      if (dir != null) {
        for (WatchEvent<?> event : key.pollEvents()) {
          if (event.context() instanceof Path relative) {
            dispatch(watchService, dir, dir.resolve(relative), event.kind());
          }
        }
      }
      if (!key.reset()) {
        watchedDirs.remove(key);
        if (agentsDir.equals(dir)) {
          return; // 根目录不可用：整体退出监听线程
        }
        if (dir != null) {
          handleChange(dir, ENTRY_DELETE); // 子 Agent 目录被删：注销该 Agent
        }
      }
    }
  }

  // 把一个 WatchService 事件翻成对某个 Agent 的登记/刷新/注销。包级可见供单测直接调（不依赖真实事件时序）。
  //  - 根目录事件：只认直接子目录的新增（补挂监听 + 试登记）与删除（注销）；
  //  - 子 Agent 目录事件：只认 AGENT.md 的增/改（登记或刷新）与删（注销）。
  /** 把一个目录事件翻成对某个 Agent 的登记/刷新/注销。 */
  void dispatch(WatchService watchService, Path dir, Path changed, WatchEvent.Kind<?> kind) {
    if (agentsDir.equals(dir)) {
      if (kind == ENTRY_CREATE && Files.isDirectory(changed)) {
        watchDirQuietly(watchService, changed); // 新 Agent 目录：开始盯它的 AGENT.md
        handleChange(changed, ENTRY_CREATE); // AGENT.md 若随目录一起就位则立即登记
      } else if (kind == ENTRY_DELETE) {
        handleChange(changed, ENTRY_DELETE); // 子目录被删：注销
      }
      // 根目录层的 MODIFY 不处理：AGENT.md 变更由子目录自己的 watch 捕获
      return;
    }
    if (!AGENT_FILE.equals(String.valueOf(changed.getFileName()))) {
      return; // 子目录里只有 AGENT.md 的变更牵动注册
    }
    if (kind == ENTRY_DELETE) {
      handleChange(dir, ENTRY_DELETE); // AGENT.md 被删：注销该 Agent
    } else {
      handleChange(dir, ENTRY_MODIFY); // AGENT.md 增/改：登记或刷新
    }
  }

  /** 补挂对新 Agent 目录的监听；挂不上只记 WARN、不拖垮监听线程。 */
  private void watchDirQuietly(WatchService watchService, Path dir) {
    try {
      registerDir(watchService, dir);
    } catch (IOException e) {
      LOG.warn(
          "监听 Agent 目录 {} 失败：{}",
          sanitize(String.valueOf(dir.getFileName())),
          sanitize(e.getMessage()));
    }
  }

  /** 单个 Agent 目录变更 → 同一段登记/刷新/注销；坏目录记 WARN 跳过、不拖垮监听。包级可见供单测直接调。 */
  void handleChange(Path agentDir, WatchEvent.Kind<?> kind) {
    try {
      if (kind == ENTRY_DELETE) {
        lifecycle.unregisterByDir(agentDir);
      } else if (Files.isDirectory(agentDir)) {
        // CREATE/MODIFY：与 API 上传同一段；refresh 会先注销旧定时再重注册（重复编辑不残留旧 cron）
        lifecycle.refresh(agentDir);
      }
    } catch (RuntimeException e) {
      LOG.warn(
          "Agent 目录 {} 变更处理失败，跳过：{}",
          sanitize(String.valueOf(agentDir.getFileName())),
          sanitize(e.getMessage()));
    }
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
