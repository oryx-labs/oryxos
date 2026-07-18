package io.oryxos.core.agent;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 第二条录入路径的执行者（第 30 节）：实时监听 {@code .oryxos/agents/}——用 JDK {@link WatchService} 监听变更， 任何 Agent 目录新增
 * / 改 / 删都汇到与 API 上传**同一段** {@link AgentLifecycleService#register(Path)} / 注销。
 * 启动时的全量扫描由装配层既有链路完成（{@code AgentLoader.loadAll} + {@code AgentScheduler.registerAll}），
 * 本类只负责启动**之后**的实时变更——避免与启动扫描重复登记（尤其重复排定时）。
 *
 * <p>基础设施守护线程（与 25 节 {@code AgentScheduler} 调度线程同类），不把异步编程模型引进请求链路（不违反宪法七）。 单个坏目录 try/catch
 * 跳过、不拖垮监听。事件处理逻辑抽成 {@link #handleChange} 以便单测（不依赖真实事件时序）。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "lifecycle/executor 是 Spring 注入的共享单例，构造注入共享同一引用正是意图（无法也不应防御性拷贝）。")
public class WorkspaceWatcher {

  private static final Logger LOG = LoggerFactory.getLogger(WorkspaceWatcher.class);

  private final AgentLifecycleService lifecycle;
  private final Path agentsDir;
  private final Executor watcherExecutor;

  public WorkspaceWatcher(
      AgentLifecycleService lifecycle, Path oryxosRoot, Executor watcherExecutor) {
    this.lifecycle = lifecycle;
    this.agentsDir = oryxosRoot.resolve("agents");
    this.watcherExecutor = watcherExecutor;
  }

  /** 装配层 {@code @Bean(initMethod="start")} 调用：在守护线程执行器上跑监听循环（启动全量扫由既有链路完成）。 */
  public void start() {
    WatchService watchService;
    try {
      Files.createDirectories(agentsDir);
      watchService = agentsDir.getFileSystem().newWatchService();
      agentsDir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
    } catch (IOException e) {
      LOG.warn("WorkspaceWatcher 启动失败，实时监听不可用: {}", sanitize(e.getMessage()));
      return;
    }
    // 交给装配层注入的守护线程执行器（同 25 节 AgentScheduler 用 Spring 线程池，不手工 new Thread）
    watcherExecutor.execute(() -> loop(watchService));
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
      for (WatchEvent<?> event : key.pollEvents()) {
        Object context = event.context();
        if (context instanceof Path child) {
          handleChange(agentsDir.resolve(child), event.kind());
        }
      }
      if (!key.reset()) {
        return; // 监听目录不可用，退出线程
      }
    }
  }

  /** 单个 Agent 目录变更 → 同一段注册 / 注销；坏目录记 WARN 跳过、不拖垮监听。包级可见供单测直接调。 */
  void handleChange(Path agentDir, WatchEvent.Kind<?> kind) {
    try {
      if (kind == ENTRY_DELETE) {
        lifecycle.unregisterByDir(agentDir);
      } else if (Files.isDirectory(agentDir)) {
        lifecycle.register(agentDir); // CREATE/MODIFY：与 API 上传同一段 register
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
