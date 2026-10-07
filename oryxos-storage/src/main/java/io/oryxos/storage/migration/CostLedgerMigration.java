package io.oryxos.storage.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V24：050 成本账本 + llm_pricing.price_version（#476）。与 PostgreSQL 目录 {@code V24__cost_ledger.sql} 成对；
 * 存量库用 PRAGMA 探测补 price_version 列 + {@code CREATE TABLE IF NOT EXISTS} 建 cost_ledger_entries 表。
 */
final class CostLedgerMigration extends BaseSqliteMigration {

  private static final Logger log = LoggerFactory.getLogger(CostLedgerMigration.class);

  CostLedgerMigration() {
    super("24", "cost ledger");
  }

  @Override
  void migrate(Connection connection) throws SQLException {
    ensurePriceVersion(connection);
    execute(
        connection,
        "CREATE TABLE IF NOT EXISTS cost_ledger_entries ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "run_id VARCHAR(64),"
            + "task_id VARCHAR(512),"
            + "agent_name VARCHAR(255),"
            + "team_id VARCHAR(128),"
            + "provider VARCHAR(64),"
            + "model VARCHAR(128),"
            + "source_kind VARCHAR(16) NOT NULL,"
            + "source_ref VARCHAR(256),"
            + "prompt_tokens INTEGER,"
            + "completion_tokens INTEGER,"
            + "total_tokens INTEGER,"
            + "llm_cost_micros INTEGER NOT NULL DEFAULT 0,"
            + "tool_cost_micros INTEGER NOT NULL DEFAULT 0,"
            + "latency_ms INTEGER NOT NULL DEFAULT 0,"
            + "price_version INTEGER,"
            + "session_id VARCHAR(512),"
            + "trace_id VARCHAR(64),"
            + "created_at TIMESTAMP NOT NULL"
            + ")");
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_cost_ledger_run ON cost_ledger_entries (run_id)");
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_cost_ledger_agent ON cost_ledger_entries (agent_name)");
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_cost_ledger_team ON cost_ledger_entries (team_id)");
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_cost_ledger_model ON cost_ledger_entries (model)");
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_cost_ledger_trace ON cost_ledger_entries (trace_id)");
    log.info("cost_ledger_entries ready (V24 / #476)");
  }

  private static void ensurePriceVersion(Connection connection) throws SQLException {
    Set<String> columns = columns(connection, "llm_pricing");
    if (columns.isEmpty()) {
      return;
    }
    if (!columns.contains("price_version")) {
      execute(
          connection,
          "ALTER TABLE llm_pricing ADD COLUMN price_version INTEGER NOT NULL DEFAULT 1");
      log.info("llm_pricing 已补 price_version 列（050 / #476）");
    }
  }
}
