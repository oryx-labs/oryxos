package io.oryxos.storage;

// StorageModule: SQLite-backed persistence layer.
// Tables: sessions, tool_invocations (audit, day-one write), llm_calls (audit, day-one write).
// WARNING: Do NOT rely on hibernate.ddl-auto=update for schema migration on SQLite —
// use manual DDL scripts or Flyway instead.
public class StorageModule {
}
