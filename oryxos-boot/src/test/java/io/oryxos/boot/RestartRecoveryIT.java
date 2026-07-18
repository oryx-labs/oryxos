package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.core.agent.AgentScheduler;
import io.oryxos.core.agent.ScheduledTaskStore;
import io.oryxos.core.agent.ScheduledTaskView;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 第28节 harness：重启恢复。定时任务的状态与执行历史落在 SQLite，进程重启后仍在——本测试用两次独立的 Spring 上下文 指向同一个库文件与工作区来证明这一点。
 *
 * <p>第一次启动：扫描 Profile 登记任务并手动执行一次（run_count→1）；关闭上下文（模拟停机）。 第二次启动：不再执行，仅查询——run_count 仍为
 * 1、执行历史仍在，说明状态没随进程消失。
 *
 * <p>mock provider，无 key、无网络；因跑两次整机上下文较重，打 `@Tag("integration")` 默认被 gate 排除。 手动跑：{@code mvn -pl
 * oryxos-boot test -Dgroups=integration -DexcludedGroups= -Dtest=RestartRecoveryIT}。
 */
@Tag("integration")
class RestartRecoveryIT {

  private static final String TASK_ID = "restart-probe";

  @Test
  @DisplayName("任务状态与执行历史_跨进程重启仍在")
  void taskStateAndHistory_survivesRestart() throws Exception {
    Path root = seedWorkspace();
    String dbUrl = "jdbc:sqlite:" + root.resolve("restart.db");

    // —— 第一次启动：登记 + 执行一次，然后关闭（模拟停机）——
    try (ConfigurableApplicationContext ctx = boot(root, dbUrl)) {
      AgentScheduler scheduler = ctx.getBean(AgentScheduler.class);
      scheduler.runNow(TASK_ID); // 手动触发一次真实 ReAct（mock provider）

      ScheduledTaskView task = findTask(ctx.getBean(ScheduledTaskStore.class).list());
      assertEquals(1, task.runCount(), "第一次运行后 run_count 应为 1");
    }

    // —— 第二次启动：只查询，不再执行 ——
    try (ConfigurableApplicationContext ctx = boot(root, dbUrl)) {
      ScheduledTaskStore store = ctx.getBean(ScheduledTaskStore.class);
      ScheduledTaskView task = findTask(store.list());
      assertEquals(1, task.runCount(), "重启后 run_count 仍应为 1（状态没随进程丢）");
      assertEquals("success", task.lastStatus(), "重启后 last_status 仍应为 success");
      assertTrue(store.executions(TASK_ID, 10).size() >= 1, "重启后执行历史仍应查得到");
    }
  }

  private static ConfigurableApplicationContext boot(Path root, String dbUrl) {
    return new SpringApplicationBuilder(OryxOsRuntime.class)
        .properties(
            "oryxos.root=" + root,
            "oryxos.providers[0].name=mock",
            "spring.datasource.url=" + dbUrl,
            "spring.main.web-application-type=none")
        .run();
  }

  private static ScheduledTaskView findTask(List<ScheduledTaskView> list) {
    return list.stream()
        .filter(t -> TASK_ID.equals(t.taskId()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("列表里应含任务 " + TASK_ID));
  }

  private static Path seedWorkspace() throws IOException {
    Path root = Files.createTempDirectory("oryxos-restart");
    Files.createDirectories(root.resolve("memory"));
    Files.createDirectories(root.resolve("profiles"));
    Files.writeString(
        root.resolve("profiles/restart-agent.yaml"),
        """
        name: restart-agent
        description: 重启恢复自测 Agent
        identity:
          agent_name: 重启小欧
          prompt: 你是一个测试助手。
        provider:
          name: mock
          model: mock-model
        tools:
          - save_memory
          - recall_memory
        schedules:
          - id: restart-probe
            cron: "0 0 0 1 1 *"
            zone: Asia/Shanghai
            message: 重启探针一次巡检
        settings:
          max_iterations: 10
          max_history_turns: 20
        """);
    return root;
  }
}
