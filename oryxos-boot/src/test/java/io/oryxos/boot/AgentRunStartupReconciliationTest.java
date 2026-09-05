package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.core.agent.AgentExecution;
import io.oryxos.core.agent.AgentExecutionStore;
import io.oryxos.core.agent.AgentStopReasons;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

class AgentRunStartupReconciliationTest {

  @Test
  @DisplayName("启动时把遗留 QUEUED/RUNNING/CANCELLING 收口为 FAILED/PROCESS_RESTARTED，终态保持不变")
  void leftoverOpenRunsAreFailedAndTerminalRunsStayPut() throws Exception {
    Path root = seedWorkspace();
    Path database = root.resolve("reconcile.db");
    String dbUrl = "jdbc:sqlite:" + database;
    seedLeftoverRuns(dbUrl);

    try (ConfigurableApplicationContext context = boot(root, dbUrl)) {
      AgentExecutionStore store = context.getBean(AgentExecutionStore.class);
      AgentExecution queued = store.findById(1L).orElseThrow();
      AgentExecution running = store.findById(2L).orElseThrow();
      AgentExecution cancelling = store.findById(3L).orElseThrow();
      AgentExecution success = store.findById(4L).orElseThrow();

      assertEquals("FAILED", queued.status());
      assertEquals(AgentStopReasons.PROCESS_RESTARTED, queued.stopReason());
      assertEquals(AgentStopReasons.MESSAGE_PROCESS_RESTARTED, queued.errorMessage());
      assertEquals("FAILED", running.status());
      assertEquals(AgentStopReasons.PROCESS_RESTARTED, running.stopReason());
      assertEquals("FAILED", cancelling.status());
      assertEquals(AgentStopReasons.PROCESS_RESTARTED, cancelling.stopReason());
      assertEquals("SUCCESS", success.status());
      assertTrue(success.stopReason() == null || success.stopReason().isBlank());
    }
  }

  private static ConfigurableApplicationContext boot(Path root, String dbUrl) {
    return new SpringApplicationBuilder(OryxOsRuntime.class)
        .run(
            "--oryxos.root=" + root,
            "--oryxos.providers[0].name=mock",
            "--spring.datasource.url=" + dbUrl,
            "--spring.lifecycle.timeout-per-shutdown-phase=100ms",
            "--spring.main.web-application-type=none");
  }

  private static void seedLeftoverRuns(String dbUrl) throws Exception {
    try (Connection connection = DriverManager.getConnection(dbUrl);
        Statement statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE TABLE IF NOT EXISTS agent_executions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            agent_name VARCHAR(255) NOT NULL,
            source VARCHAR(32) NOT NULL,
            session_id VARCHAR(512),
            started_at TIMESTAMP NOT NULL,
            ended_at TIMESTAMP,
            success BOOLEAN,
            error_message TEXT,
            duration_ms INTEGER,
            updated_at TIMESTAMP,
            input_preview TEXT,
            cancel_requested_at TIMESTAMP,
            status VARCHAR(32),
            stop_reason VARCHAR(64)
          )
          """);
      statement.execute(
          """
          INSERT INTO agent_executions
            (agent_name, source, started_at, updated_at, status, input_preview)
          VALUES
            ('ops', 'manual', '2026-08-23 04:00:00', '2026-08-23 04:00:00', 'QUEUED', 'queued')
          """);
      statement.execute(
          """
          INSERT INTO agent_executions
            (agent_name, source, started_at, updated_at, status, input_preview)
          VALUES
            ('ops', 'manual', '2026-08-23 04:00:01', '2026-08-23 04:00:01', 'RUNNING', 'running')
          """);
      statement.execute(
          """
          INSERT INTO agent_executions
            (agent_name, source, started_at, updated_at, status, cancel_requested_at, input_preview)
          VALUES
            ('ops', 'manual', '2026-08-23 04:00:02', '2026-08-23 04:00:03', 'CANCELLING',
             '2026-08-23 04:00:03', 'cancelling')
          """);
      statement.execute(
          """
          INSERT INTO agent_executions
            (agent_name, source, started_at, ended_at, updated_at, success, status, duration_ms, input_preview)
          VALUES
            ('ops', 'manual', '2026-08-23 03:00:00', '2026-08-23 03:00:02', '2026-08-23 03:00:02',
             1, 'SUCCESS', 2000, 'done')
          """);
    }
  }

  private static Path seedWorkspace() throws Exception {
    Path root = Files.createTempDirectory("oryxos-run-reconcile");
    Files.createDirectories(root.resolve("memory"));
    Files.createDirectories(root.resolve("agents").resolve("ops"));
    Files.writeString(
        root.resolve("agents/ops/AGENT.md"),
        """
        ---
        name: ops
        description: reconcile fixture
        identity:
          agent_name: Ops
          prompt: You are a test agent.
        provider:
          name: mock
          model: mock-model
        tools:
          - save_memory
        settings:
          max_iterations: 1
          max_history_turns: 1
        ---
        Test fixture.
        """);
    return root;
  }
}
