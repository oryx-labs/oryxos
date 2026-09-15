package io.oryxos.storage.migration;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * V10：040 OIDC/SSO——外部身份映射（oidc_identities）+ 认证事件审计（auth_events）+ 授权流程临时状态 （oidc_auth_requests）。与
 * PostgreSQL 目录的 {@code V10__oidc_sso.sql} 成对。全部 CREATE IF NOT EXISTS， 幂等重跑支撑 MigrationEvolutionIT
 * 尾部中断恢复（与 V8/V9 同语义）。
 */
final class OidcSsoMigration extends BaseSqliteMigration {

  OidcSsoMigration() {
    super("10", "oidc sso");
  }

  @Override
  void migrate(Connection connection) throws SQLException {
    execute(
        connection,
        "CREATE TABLE IF NOT EXISTS oidc_identities ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "issuer VARCHAR(255) NOT NULL,"
            + "subject VARCHAR(255) NOT NULL,"
            + "username VARCHAR(64) NOT NULL,"
            + "email VARCHAR(255),"
            + "first_login_at TIMESTAMP NOT NULL,"
            + "last_login_at TIMESTAMP NOT NULL,"
            + "CONSTRAINT uq_oidc_identities_issuer_subject UNIQUE (issuer, subject))");
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_oidc_identities_username"
            + " ON oidc_identities (username)");
    execute(
        connection,
        "CREATE TABLE IF NOT EXISTS auth_events ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "event_type VARCHAR(32) NOT NULL,"
            + "auth_method VARCHAR(16) NOT NULL,"
            + "username VARCHAR(64),"
            + "external_issuer VARCHAR(255),"
            + "external_subject VARCHAR(255),"
            + "roles VARCHAR(255),"
            + "failure_reason VARCHAR(64),"
            + "source_ip VARCHAR(64),"
            + "trace_id VARCHAR(64),"
            + "created_at TIMESTAMP NOT NULL)");
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_auth_events_created_at ON auth_events (created_at)");
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_auth_events_subject"
            + " ON auth_events (external_subject, created_at)");
    execute(
        connection,
        "CREATE INDEX IF NOT EXISTS idx_auth_events_username"
            + " ON auth_events (username, created_at)");
    execute(
        connection,
        "CREATE TABLE IF NOT EXISTS oidc_auth_requests ("
            + "state VARCHAR(64) PRIMARY KEY,"
            + "nonce VARCHAR(64) NOT NULL,"
            + "pkce_verifier VARCHAR(128) NOT NULL,"
            + "created_at TIMESTAMP NOT NULL,"
            + "expires_at TIMESTAMP NOT NULL,"
            + "consumed_at TIMESTAMP)");
  }
}
