package io.oryxos.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class ScheduleSchemaUpgradeTest {

  @TempDir Path tempDir;

  @Test
  void upgradesLegacySchemaWithoutDroppingTasksOrExecutionHistory() throws Exception {
    SQLiteDataSource dataSource = dataSource("legacy.db");
    createLegacySchema(dataSource);
    execute(
        dataSource,
        """
        INSERT INTO scheduled_tasks (task_id, profile_name, cron, zone, message, enabled,
            next_run_at, last_run_at, last_status, run_count, updated_at)
        VALUES ('daily', 'alpha', '0 0 9 * * *', 'Asia/Shanghai', 'morning', 0,
            '2026-08-15T01:00:00Z', '2026-08-14T01:00:00Z', 'success', 7, '2026-08-14T01:00:00Z')
        """,
        """
        INSERT INTO task_executions (task_id, session_id, started_at, success, error_message, duration_ms)
        VALUES ('daily', 'session-1', '2026-08-14T01:00:00Z', 1, NULL, 123),
               ('orphan', 'session-2', '2026-08-14T02:00:00Z', 0, 'gone', 456)
        """);

    new ScheduleSchemaUpgrade(dataSource).upgrade();

    try (Connection connection = dataSource.getConnection()) {
      assertThat(count(connection, "scheduled_tasks")).isEqualTo(1);
      assertThat(count(connection, "task_executions")).isEqualTo(2);
      assertThat(columnNames(connection, "scheduled_tasks"))
          .contains("schedule_id", "schedule_key", "display_name")
          .doesNotContain("task_id");
      assertThat(columnNames(connection, "task_executions"))
          .contains("schedule_id", "legacy_task_key", "legacy_migrated")
          .doesNotContain("task_id");

      try (Statement statement = connection.createStatement();
          ResultSet resultSet =
              statement.executeQuery(
                  "SELECT schedule_id, profile_name, schedule_key, display_name, enabled, retired, run_count"
                      + " FROM scheduled_tasks")) {
        assertThat(resultSet.next()).isTrue();
        String scheduleId = resultSet.getString("schedule_id");
        assertThat(scheduleId).isNotBlank();
        assertThat(resultSet.getString("profile_name")).isEqualTo("alpha");
        assertThat(resultSet.getString("schedule_key")).isEqualTo("daily");
        assertThat(resultSet.getString("display_name")).isEqualTo("daily");
        assertThat(resultSet.getBoolean("enabled")).isFalse();
        assertThat(resultSet.getBoolean("retired")).isFalse();
        assertThat(resultSet.getLong("run_count")).isEqualTo(7);

        try (Statement historyStatement = connection.createStatement();
            ResultSet histories =
                historyStatement.executeQuery(
                    "SELECT schedule_id, legacy_task_key, legacy_migrated FROM task_executions ORDER BY id")) {
          assertThat(histories.next()).isTrue();
          assertThat(histories.getString("schedule_id")).isEqualTo(scheduleId);
          assertThat(histories.getString("legacy_task_key")).isEqualTo("daily");
          assertThat(histories.getBoolean("legacy_migrated")).isTrue();
          assertThat(histories.next()).isTrue();
          assertThat(histories.getString("schedule_id")).isNull();
          assertThat(histories.getString("legacy_task_key")).isEqualTo("orphan");
          assertThat(histories.getBoolean("legacy_migrated")).isTrue();
        }
      }
    }
  }

  @Test
  void isIdempotentAfterLegacySchemaHasBeenUpgraded() throws Exception {
    SQLiteDataSource dataSource = dataSource("idempotent.db");
    createLegacySchema(dataSource);
    execute(
        dataSource,
        """
        INSERT INTO scheduled_tasks (task_id, profile_name, cron, enabled, run_count, updated_at)
        VALUES ('daily', 'alpha', '0 0 9 * * *', 1, 0, '2026-08-14T01:00:00Z')
        """);

    ScheduleSchemaUpgrade upgrade = new ScheduleSchemaUpgrade(dataSource);
    upgrade.upgrade();
    upgrade.upgrade();

    try (Connection connection = dataSource.getConnection()) {
      assertThat(count(connection, "scheduled_tasks")).isEqualTo(1);
      assertThat(count(connection, "task_executions")).isZero();
    }
  }

  @Test
  void addsRetiredFlagToTheFirstScheduleIdSchemaWithoutLosingRows() throws Exception {
    SQLiteDataSource dataSource = dataSource("pre-retirement-current.db");
    createPreRetirementCurrentSchema(dataSource);
    execute(
        dataSource,
        """
        INSERT INTO scheduled_tasks (schedule_id, profile_name, schedule_key, display_name, cron, enabled,
            run_count, updated_at)
        VALUES ('11111111-1111-1111-1111-111111111111', 'alpha', 'daily', 'Daily',
            '0 0 9 * * *', 1, 2, '2026-08-14T01:00:00Z')
        """);

    new ScheduleSchemaUpgrade(dataSource).upgrade();

    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "SELECT retired FROM scheduled_tasks WHERE schedule_key = 'daily'")) {
      assertThat(columnNames(connection, "scheduled_tasks")).contains("retired");
      assertThat(count(connection, "scheduled_tasks")).isEqualTo(1);
      assertThat(resultSet.next()).isTrue();
      assertThat(resultSet.getBoolean("retired")).isFalse();
    }
  }

  @Test
  void rejectsMixedOrUnknownScheduleTablesRatherThanGuessingAtData() throws Exception {
    SQLiteDataSource dataSource = dataSource("unknown.db");
    execute(
        dataSource,
        "CREATE TABLE scheduled_tasks (schedule_id TEXT PRIMARY KEY, profile_name TEXT NOT NULL)",
        "CREATE TABLE task_executions (id INTEGER PRIMARY KEY, task_id TEXT NOT NULL)");

    assertThatThrownBy(() -> new ScheduleSchemaUpgrade(dataSource).upgrade())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Unsupported scheduled-task schema");
  }

  @Test
  void rejectsCurrentColumnsWhenTheIdentityConstraintsAreMissing() throws Exception {
    SQLiteDataSource dataSource = dataSource("missing-constraints.db");
    execute(
        dataSource,
        "CREATE TABLE scheduled_tasks (schedule_id TEXT, profile_name TEXT, schedule_key TEXT, display_name TEXT, cron TEXT, zone TEXT, message TEXT, enabled BOOLEAN, retired BOOLEAN, next_run_at TEXT, last_run_at TEXT, last_status TEXT, run_count INTEGER, updated_at TEXT)",
        "CREATE TABLE task_executions (id INTEGER, schedule_id TEXT, legacy_task_key TEXT, legacy_migrated BOOLEAN, session_id TEXT, started_at TEXT, success BOOLEAN, error_message TEXT, duration_ms INTEGER)");

    assertThatThrownBy(() -> new ScheduleSchemaUpgrade(dataSource).upgrade())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Unsupported scheduled-task schema");
  }

  private SQLiteDataSource dataSource(String fileName) {
    SQLiteDataSource dataSource = new SQLiteDataSource();
    dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve(fileName));
    return dataSource;
  }

  private static void createLegacySchema(SQLiteDataSource dataSource) throws Exception {
    execute(
        dataSource,
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
        """,
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
  }

  private static void createPreRetirementCurrentSchema(SQLiteDataSource dataSource)
      throws Exception {
    execute(
        dataSource,
        """
        CREATE TABLE scheduled_tasks (
          schedule_id VARCHAR(36) PRIMARY KEY,
          profile_name VARCHAR(255) NOT NULL,
          schedule_key VARCHAR(255) NOT NULL,
          display_name VARCHAR(255) NOT NULL,
          cron VARCHAR(128) NOT NULL,
          zone VARCHAR(64),
          message TEXT,
          enabled BOOLEAN NOT NULL DEFAULT 1,
          next_run_at TIMESTAMP,
          last_run_at TIMESTAMP,
          last_status VARCHAR(16),
          run_count INTEGER NOT NULL DEFAULT 0,
          updated_at TIMESTAMP NOT NULL,
          UNIQUE (profile_name, schedule_key)
        )
        """,
        """
        CREATE TABLE task_executions (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          schedule_id VARCHAR(36),
          legacy_task_key VARCHAR(255),
          legacy_migrated BOOLEAN NOT NULL DEFAULT 0,
          session_id VARCHAR(512),
          started_at TIMESTAMP NOT NULL,
          success BOOLEAN NOT NULL,
          error_message TEXT,
          duration_ms INTEGER NOT NULL
        )
        """);
  }

  private static void execute(SQLiteDataSource dataSource, String... statements) throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String sql : statements) {
        statement.execute(sql);
      }
    }
  }

  private static long count(Connection connection, String table) throws Exception {
    try (Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
      assertThat(resultSet.next()).isTrue();
      return resultSet.getLong(1);
    }
  }

  private static java.util.List<String> columnNames(Connection connection, String table)
      throws Exception {
    try (Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
      java.util.List<String> names = new java.util.ArrayList<>();
      while (resultSet.next()) {
        names.add(resultSet.getString("name"));
      }
      return names;
    }
  }
}
