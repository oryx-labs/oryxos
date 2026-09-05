package io.oryxos.storage;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 存量 SQLite 幂等升级：给 agent_executions 补 Run 元数据列，并确保 agent_run_events 存在。
 *
 * <p>新装库由 Flyway V1 全量建表（SQLite / PostgreSQL 基线都已含工作台列与事件表）。本升级器只处理缺列/缺表的旧 SQLite 库；PostgreSQL
 * 上直接跳过（PRAGMA 与 AUTOINCREMENT 都是 SQLite 方言）。补列 SQL 全部写死，避免动态拼表名/列名。
 */
public final class AgentRunSchemaUpgrade {

  private static final Logger LOG = LoggerFactory.getLogger(AgentRunSchemaUpgrade.class);

  private static final String UPDATED_AT = "updated_at";
  private static final String INPUT_PREVIEW = "input_preview";
  private static final String CANCEL_REQUESTED_AT = "cancel_requested_at";
  private static final String STATUS = "status";
  private static final String STOP_REASON = "stop_reason";

  private final DataSource dataSource;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "The injected DataSource is a shared connection factory.")
  public AgentRunSchemaUpgrade(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public void upgrade() {
    try (Connection connection = dataSource.getConnection()) {
      if (!isSqlite(connection)) {
        return;
      }
      ensureExecutionColumns(connection);
      ensureEventTable(connection);
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to upgrade agent run schema", e);
    }
  }

  private static boolean isSqlite(Connection connection) throws SQLException {
    return connection
        .getMetaData()
        .getDatabaseProductName()
        .toLowerCase(Locale.ROOT)
        .contains("sqlite");
  }

  private static void ensureExecutionColumns(Connection connection) throws SQLException {
    Set<String> columns = executionColumns(connection);
    if (columns.isEmpty()) {
      return;
    }
    try (Statement statement = connection.createStatement()) {
      if (!columns.contains(UPDATED_AT)) {
        statement.execute("ALTER TABLE agent_executions ADD COLUMN updated_at TIMESTAMP");
        LOG.info("agent_executions 已补 updated_at 列");
      }
      if (!columns.contains(INPUT_PREVIEW)) {
        statement.execute("ALTER TABLE agent_executions ADD COLUMN input_preview TEXT");
        LOG.info("agent_executions 已补 input_preview 列");
      }
      if (!columns.contains(CANCEL_REQUESTED_AT)) {
        statement.execute("ALTER TABLE agent_executions ADD COLUMN cancel_requested_at TIMESTAMP");
        LOG.info("agent_executions 已补 cancel_requested_at 列");
      }
      if (!columns.contains(STATUS)) {
        statement.execute("ALTER TABLE agent_executions ADD COLUMN status VARCHAR(32)");
        LOG.info("agent_executions 已补 status 列");
      }
      if (!columns.contains(STOP_REASON)) {
        statement.execute("ALTER TABLE agent_executions ADD COLUMN stop_reason VARCHAR(64)");
        LOG.info("agent_executions 已补 stop_reason 列");
      }
      statement.execute(
          "CREATE INDEX IF NOT EXISTS idx_agent_executions_started ON agent_executions (started_at, id)");
      statement.execute(
          "UPDATE agent_executions SET status = 'RUNNING' WHERE status IS NULL AND ended_at IS NULL");
      statement.execute(
          "UPDATE agent_executions SET status = 'SUCCESS' WHERE status IS NULL AND success = 1");
      statement.execute(
          "UPDATE agent_executions SET status = 'FAILED' WHERE status IS NULL AND ended_at IS NOT NULL");
      statement.execute(
          "UPDATE agent_executions SET updated_at = COALESCE(ended_at, started_at) WHERE updated_at IS NULL");
    }
  }

  private static void ensureEventTable(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE TABLE IF NOT EXISTS agent_run_events (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              run_id INTEGER NOT NULL,
              sequence INTEGER NOT NULL,
              type VARCHAR(64) NOT NULL,
              created_at TIMESTAMP NOT NULL,
              payload_json TEXT NOT NULL,
              UNIQUE (run_id, sequence)
          )
          """);
      statement.execute(
          "CREATE INDEX IF NOT EXISTS idx_agent_run_events_run_seq ON agent_run_events (run_id, sequence)");
    }
  }

  private static Set<String> executionColumns(Connection connection) throws SQLException {
    Set<String> columns = new HashSet<>();
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("PRAGMA table_info(agent_executions)")) {
      while (rows.next()) {
        columns.add(rows.getString("name"));
      }
    }
    return columns;
  }
}
