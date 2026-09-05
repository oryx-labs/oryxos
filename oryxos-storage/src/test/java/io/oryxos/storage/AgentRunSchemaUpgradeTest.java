package io.oryxos.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class AgentRunSchemaUpgradeTest {

  @TempDir Path tempDir;

  @Test
  void addsRunColumnsAndEventTableToLegacyExecutions() throws Exception {
    SQLiteDataSource dataSource = dataSource("legacy-run.db");
    execute(
        dataSource,
        """
        CREATE TABLE agent_executions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            agent_name VARCHAR(255) NOT NULL,
            source VARCHAR(32) NOT NULL,
            session_id VARCHAR(512),
            started_at TIMESTAMP NOT NULL,
            ended_at TIMESTAMP,
            success BOOLEAN,
            error_message TEXT,
            duration_ms INTEGER
        )
        """,
        "INSERT INTO agent_executions (agent_name, source, started_at, ended_at, success, duration_ms)"
            + " VALUES ('ops', 'manual', '2026-08-01T00:00:00Z', '2026-08-01T00:00:02Z', 1, 2000)");

    AgentRunSchemaUpgrade upgrade = new AgentRunSchemaUpgrade(dataSource);
    upgrade.upgrade();
    upgrade.upgrade();

    assertThat(columns(dataSource, "agent_executions"))
        .contains("updated_at", "input_preview", "cancel_requested_at", "status", "stop_reason");
    assertThat(columns(dataSource, "agent_run_events"))
        .containsExactlyInAnyOrder(
            "id", "run_id", "sequence", "type", "created_at", "payload_json");

    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT status FROM agent_executions")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getString("status")).isEqualTo("SUCCESS");
    }
  }

  private SQLiteDataSource dataSource(String name) {
    SQLiteDataSource dataSource = new SQLiteDataSource();
    dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve(name));
    return dataSource;
  }

  private static void execute(SQLiteDataSource dataSource, String... sqls) throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String sql : sqls) {
        statement.execute(sql);
      }
    }
  }

  private static Set<String> columns(SQLiteDataSource dataSource, String table) throws Exception {
    Set<String> columns = new HashSet<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
      while (rows.next()) {
        columns.add(rows.getString("name"));
      }
    }
    return columns;
  }
}
