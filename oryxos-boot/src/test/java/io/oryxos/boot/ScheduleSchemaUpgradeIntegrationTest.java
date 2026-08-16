package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.core.agent.AgentScheduler;
import io.oryxos.storage.ScheduleSchemaUpgrade;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Verifies that a real application start upgrades a recognized pre-scheduleId SQLite database. */
class ScheduleSchemaUpgradeIntegrationTest {

  @Test
  @DisplayName("startup upgrades legacy schedule tables without losing task or execution rows")
  void startupUpgradesLegacySchemaAndPreservesRows() throws Exception {
    Path root = seedWorkspace();
    Path database = root.resolve("legacy-schedules.db");
    String dbUrl = "jdbc:sqlite:" + database;
    createLegacyDatabase(dbUrl);

    try (ConfigurableApplicationContext context = boot(root, dbUrl)) {
      // The scheduler is an eager dependency of the normal web runtime; resolve it here because
      // this fixture deliberately uses WebApplicationType.NONE to avoid binding a server port.
      assertNotNull(context.getBean(AgentScheduler.class));
      assertEquals(dbUrl, jdbcUrl(context.getBean(DataSource.class)));
      context.getBean(ScheduleSchemaUpgrade.class).upgrade();
      assertNewSchemaAndPreservedRows(dbUrl);
    }

    assertNewSchemaAndPreservedRows(dbUrl);
  }

  private static String jdbcUrl(DataSource dataSource) throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      return connection.getMetaData().getURL();
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

  private static void createLegacyDatabase(String dbUrl) throws Exception {
    try (Connection connection = DriverManager.getConnection(dbUrl);
        Statement statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE TABLE scheduled_tasks (
            task_id VARCHAR(255) PRIMARY KEY,
            profile_name VARCHAR(255) NOT NULL,
            cron VARCHAR(128) NOT NULL,
            zone VARCHAR(64),
            message TEXT,
            enabled BOOLEAN NOT NULL DEFAULT 1,
            next_run_at TIMESTAMP,
            last_run_at TIMESTAMP,
            last_status VARCHAR(16),
            run_count INTEGER NOT NULL DEFAULT 0,
            updated_at TIMESTAMP NOT NULL
          )
          """);
      statement.execute(
          """
          CREATE TABLE task_executions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            task_id VARCHAR(255) NOT NULL,
            session_id VARCHAR(512),
            started_at TIMESTAMP NOT NULL,
            success BOOLEAN NOT NULL,
            error_message TEXT,
            duration_ms INTEGER NOT NULL
          )
          """);
      statement.execute(
          """
          INSERT INTO scheduled_tasks (
            task_id, profile_name, cron, zone, message, enabled, next_run_at, last_run_at,
            last_status, run_count, updated_at)
          VALUES (
            'daily', 'legacy-agent', '0 0 0 1 1 *', 'Asia/Shanghai', 'legacy message', 0,
            '2026-01-01T00:00:00Z', '2025-12-31T00:00:00Z', 'success', 7,
            '2025-12-31T00:00:00Z')
          """);
      statement.execute(
          """
          INSERT INTO task_executions (
            task_id, session_id, started_at, success, error_message, duration_ms)
          VALUES ('daily', 'legacy-session', '2025-12-31T00:00:00Z', 1, NULL, 42)
          """);
    }
  }

  private static void assertNewSchemaAndPreservedRows(String dbUrl) throws Exception {
    try (Connection connection = DriverManager.getConnection(dbUrl);
        Statement statement = connection.createStatement()) {
      assertFalse(columnExists(statement, "scheduled_tasks", "task_id"));
      assertTrue(columnExists(statement, "scheduled_tasks", "schedule_id"));
      assertTrue(columnExists(statement, "task_executions", "schedule_id"));
      assertTrue(columnExists(statement, "task_executions", "legacy_task_key"));
      assertTrue(columnExists(statement, "task_executions", "legacy_migrated"));
      assertEquals(1, count(statement, "scheduled_tasks"));
      assertEquals(1, count(statement, "task_executions"));

      try (ResultSet task =
          statement.executeQuery(
              "SELECT schedule_id, profile_name, schedule_key, display_name, enabled, run_count"
                  + " FROM scheduled_tasks")) {
        assertTrue(task.next());
        assertNotNull(task.getString("schedule_id"));
        assertFalse(task.getString("schedule_id").isBlank());
        assertEquals("legacy-agent", task.getString("profile_name"));
        assertEquals("daily", task.getString("schedule_key"));
        assertEquals("daily", task.getString("display_name"));
        assertFalse(task.getBoolean("enabled"));
        assertEquals(7, task.getInt("run_count"));
      }

      try (ResultSet execution =
          statement.executeQuery(
              "SELECT schedule_id, legacy_task_key, legacy_migrated FROM task_executions")) {
        assertTrue(execution.next());
        assertNotNull(execution.getString("schedule_id"));
        assertEquals("daily", execution.getString("legacy_task_key"));
        assertTrue(execution.getBoolean("legacy_migrated"));
      }
    }
  }

  private static boolean columnExists(Statement statement, String table, String column)
      throws Exception {
    try (ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
      while (rows.next()) {
        if (column.equals(rows.getString("name"))) {
          return true;
        }
      }
      return false;
    }
  }

  private static long count(Statement statement, String table) throws Exception {
    try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
      assertTrue(rows.next());
      return rows.getLong(1);
    }
  }

  private static Path seedWorkspace() throws IOException {
    Path root = Files.createTempDirectory("oryxos-legacy-schedule-upgrade");
    Files.createDirectories(root.resolve("memory"));
    Files.createDirectories(root.resolve("agents").resolve("legacy-agent"));
    Files.writeString(
        root.resolve("agents/legacy-agent/AGENT.md"),
        """
        ---
        name: legacy-agent
        description: legacy upgrade fixture
        identity:
          agent_name: Legacy Agent
          prompt: You are a test agent.
        provider:
          name: mock
          model: mock-model
        tools:
          - save_memory
        schedules:
          - key: daily
            name: Daily check
            cron: "0 0 0 1 1 *"
            zone: Asia/Shanghai
            message: current message
        settings:
          max_iterations: 1
          max_history_turns: 1
        ---
        Test fixture.
        """);
    return root;
  }
}
