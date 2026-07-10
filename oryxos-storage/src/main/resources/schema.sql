-- llm_calls：LLM 调用审计（宪法 V：Day One 落库）
-- SQLite ALTER TABLE 能力弱：本脚本是表结构唯一权威，禁用 hibernate.ddl-auto=update
CREATE TABLE IF NOT EXISTS llm_calls (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id VARCHAR(255) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    total_tokens INTEGER,
    success BOOLEAN NOT NULL,
    error_message TEXT,
    duration_ms INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_llm_calls_session ON llm_calls (session_id);
