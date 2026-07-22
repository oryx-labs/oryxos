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

-- scheduled_tasks：定时任务登记 + 运行状态（28 节）。定义来源是 skill/Profile 的 schedules（重启从文件重新注册）；
-- 本表存"任务状态 + 下次触发"，重启后状态/历史仍在，管理台可看可管（启用/停用、立即执行）。
CREATE TABLE IF NOT EXISTS scheduled_tasks (
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
);

-- task_executions：定时任务每次执行的历史（28 节；成功失败都记，重启不丢，管理台可回看）
CREATE TABLE IF NOT EXISTS task_executions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id VARCHAR(255) NOT NULL,
    session_id VARCHAR(512),
    started_at TIMESTAMP NOT NULL,
    success BOOLEAN NOT NULL,
    error_message TEXT,
    duration_ms INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_task_executions_task ON task_executions (task_id);

-- memory_entries：长期记忆条目（SqliteMemoryStore 后端，22 节）
-- scope=CORE 全量注入不截断；scope=ARCHIVAL 归档只带最近 N 条（查询 LIMIT，非删除）
CREATE TABLE IF NOT EXISTS memory_entries (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    scope VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_memory_scope ON memory_entries (scope);

-- notify_channels：全局通知渠道注册表（31 节）——name → type + url + 描述；管理台可 CRUD、Agent 按名字引用
-- （notify 工具的 channel 参数）。新表，CREATE TABLE IF NOT EXISTS，非 ALTER，无迁移风险。
CREATE TABLE IF NOT EXISTS notify_channels (
    name VARCHAR(128) PRIMARY KEY,
    type VARCHAR(32) NOT NULL,
    url TEXT NOT NULL,
    description TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- providers：LLM Provider 动态注册表（31 节）——name → api_key + base_url + 描述；管理台可 CRUD、运行时按名动态建 ChatModel。
-- 启动时把 config/application.yml 的 oryxos.providers 播种进来（库里没有才写），之后以本表为准。
-- 注意：api_key 明文落库（本地 gitignored 库）——这是"可动态管理"对宪法"凭证走环境变量"的核心阶段让步。
CREATE TABLE IF NOT EXISTS providers (
    name VARCHAR(128) PRIMARY KEY,
    api_key TEXT,
    base_url TEXT,
    description TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- sandbox_whitelist：Sandbox 白名单持久化（宪法 VI 第一档）——三类 category（FILE/SHELL/HTTP）→ entry_value。
-- 启动时把 config 的 file.allowed_paths / shell.allowed_commands / http.allowed_domains 播种进来（幂等，库里没有才写），
-- 之后管理台 / API 的增删即刻落库，重启保留。entry_value 存"入内存的规范形"（FILE 为归一后的绝对路径）以便与 list/删除对齐。
CREATE TABLE IF NOT EXISTS sandbox_whitelist (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    category VARCHAR(16) NOT NULL,
    entry_value TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (category, entry_value)
);
