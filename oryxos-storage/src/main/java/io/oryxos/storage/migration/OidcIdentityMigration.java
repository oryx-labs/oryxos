package io.oryxos.storage.migration;

import java.sql.Connection;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V10：040 OIDC 身份映射与认证事件。新表用 {@code CREATE TABLE IF NOT EXISTS}；与 PostgreSQL 目录 {@code
 * V10__oidc_identity.sql} 成对。幂等重跑支撑 MigrationEvolutionIT。
 */
final class OidcIdentityMigration extends BaseSqliteMigration {

  private static final Logger log = LoggerFactory.getLogger(OidcIdentityMigration.class);

  OidcIdentityMigration() {
    super("10", "oidc identity mappings and auth events");
  }

  @Override
  void migrate(Connection connection) throws SQLException {
    ensureIdentityMappings(connection);
    ensureAuthEvents(connection);
    log.info("identity_mappings / auth_events 已收敛");
  }

  private static void ensureIdentityMappings(Connection connection) throws SQLException {
    execute(
        connection,
        "CREATE TABLE IF NOT EXISTS identity_mappings ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "issuer VARCHAR(512) NOT NULL,"
            + "subject VARCHAR(512) NOT NULL,"
            + "username VARCHAR(64) NOT NULL,"
            + "email VARCHAR(320),"
            + "created_at TIMESTAMP NOT NULL,"
            + "updated_at TIMESTAMP NOT NULL,"
            + "CONSTRAINT uq_identity_mappings_issuer_subject UNIQUE (issuer, subject))");
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_identity_mappings_username ON identity_mappings (username)");
  }

  private static void ensureAuthEvents(Connection connection) throws SQLException {
    execute(
        connection,
        "CREATE TABLE IF NOT EXISTS auth_events ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "event_type VARCHAR(32) NOT NULL,"
            + "principal_id VARCHAR(128),"
            + "detail VARCHAR(1024),"
            + "created_at TIMESTAMP NOT NULL)");
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_auth_events_created_at ON auth_events (created_at)");
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_auth_events_type_created ON auth_events (event_type, created_at)");
  }
}
