package io.oryxos.storage.migration;

import java.sql.Connection;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V11：041 资产治理变更审计。新表用 {@code CREATE TABLE IF NOT EXISTS}；与 PostgreSQL 目录 {@code
 * V11__asset_governance.sql} 成对。幂等重跑支撑 MigrationEvolutionIT。
 */
final class AssetGovernanceEventsMigration extends BaseSqliteMigration {

  private static final Logger log = LoggerFactory.getLogger(AssetGovernanceEventsMigration.class);

  AssetGovernanceEventsMigration() {
    super("11", "asset governance events");
  }

  @Override
  void migrate(Connection connection) throws SQLException {
    execute(
        connection,
        "CREATE TABLE IF NOT EXISTS asset_governance_events ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "actor VARCHAR(128) NOT NULL,"
            + "resource_type VARCHAR(32) NOT NULL,"
            + "resource_id VARCHAR(255) NOT NULL,"
            + "change_summary VARCHAR(512) NOT NULL,"
            + "created_at TIMESTAMP NOT NULL)");
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_asset_gov_events_created"
            + " ON asset_governance_events (created_at)");
    log.info("asset_governance_events 已收敛");
  }
}
