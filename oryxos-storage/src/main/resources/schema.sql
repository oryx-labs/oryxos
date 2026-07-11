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

-- tool_invocations：工具调用审计（宪法 V：Day One 落库，成功要记、失败也要记）
CREATE TABLE IF NOT EXISTS tool_invocations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id VARCHAR(255) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    input_json TEXT,
    result_json TEXT,
    success BOOLEAN NOT NULL,
    error_message TEXT,
    duration_ms INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tool_invocations_session ON tool_invocations (session_id);

-- sessions：会话元数据 + JSON 序列化的对话历史（18 节）
-- session_id 由 SessionManager 按 channel:user:profile 唯一拼接（全库唯一拼接点，H4④）
CREATE TABLE IF NOT EXISTS sessions (
    session_id VARCHAR(512) PRIMARY KEY,
    profile_name VARCHAR(255) NOT NULL,
    channel VARCHAR(64) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    messages_json TEXT,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    last_active_at TIMESTAMP,
    archived_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_sessions_profile ON sessions (profile_name);
