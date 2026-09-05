-- V1 基线（025-pluggable-storage）：原 schema.sql 原样平移。全部 CREATE TABLE IF NOT EXISTS 幂等——
-- 存量库（baseline-version=0 接管）与新库走同一序列；后加列与其索引归 V2~V5 Java migration（原升级类平移）。
-- 表结构唯一权威是 db/migration/{vendor} 迁移目录，禁用 hibernate.ddl-auto=update（宪法 VIII）。

-- llm_calls：LLM 调用审计（宪法 V：Day One 落库）
CREATE TABLE IF NOT EXISTS llm_calls (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id VARCHAR(255) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    total_tokens INTEGER,
    cost_micros INTEGER,
    profile_name VARCHAR(255),
    trace_id VARCHAR(64),
    success BOOLEAN NOT NULL,
    error_message TEXT,
    duration_ms INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_llm_calls_session ON llm_calls (session_id);
-- idx_llm_calls_profile (profile_name) / idx_llm_calls_trace (trace_id) 由 V2 迁移创建：
-- 存量库跑到本脚本时还没有这些后加列，在这里建索引会让启动失败（idx_memory_agent 教训）。

-- tool_invocations：工具调用审计（宪法 V：Day One 落库，成功要记、失败也要记）
CREATE TABLE IF NOT EXISTS tool_invocations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id VARCHAR(255) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    input_json TEXT,
    result_json TEXT,
    profile_name VARCHAR(255),
    trace_id VARCHAR(64),
    success BOOLEAN NOT NULL,
    error_message TEXT,
    blocked_by VARCHAR(16),
    execution_backend VARCHAR(8),
    container_id VARCHAR(64),
    duration_ms INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tool_invocations_session ON tool_invocations (session_id);
-- idx_tool_invocations_profile (profile_name) / idx_tool_invocations_trace (trace_id) 由 V2 迁移创建（同上）。
-- execution_backend / container_id（024 容器级执行隔离）：local 档恒写 backend、docker 档另记容器 ID；历史行 NULL ≡ local
--（查询层兼容）。存量库补列归 V2 迁移（024 原 AuditSchemaUpgrade 的补列逻辑随 025 收编）。

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
    schedule_id VARCHAR(36) PRIMARY KEY,
    profile_name VARCHAR(255) NOT NULL,
    schedule_key VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    cron VARCHAR(128) NOT NULL,
    zone VARCHAR(64),
    message TEXT,
    enabled BOOLEAN NOT NULL DEFAULT 1,
    retired BOOLEAN NOT NULL DEFAULT 0,
    next_run_at TIMESTAMP,
    last_run_at TIMESTAMP,
    last_status VARCHAR(16),
    run_count INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE (profile_name, schedule_key)
);

-- task_executions：定时任务每次执行的历史（28 节；成功失败都记，重启不丢，管理台可回看）
CREATE TABLE IF NOT EXISTS task_executions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    schedule_id VARCHAR(36),
    legacy_task_key VARCHAR(255),
    legacy_migrated BOOLEAN NOT NULL DEFAULT 0,
    session_id VARCHAR(512),
    started_at TIMESTAMP NOT NULL,
    success BOOLEAN NOT NULL,
    error_message TEXT,
    duration_ms INTEGER NOT NULL
);

-- agent_executions：Agent 维度的每次执行历史（第 32 节；手动触发 / 定时触发都记，含起止时间与状态）
-- ended_at 为空表示"运行中"；成功失败都记，重启不丢，管理台按 Agent 回看。
CREATE TABLE IF NOT EXISTS agent_executions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    agent_name VARCHAR(255) NOT NULL,
    source VARCHAR(32) NOT NULL,
    session_id VARCHAR(512),
    trace_id VARCHAR(64),
    started_at TIMESTAMP NOT NULL,
    ended_at TIMESTAMP,
    success BOOLEAN,
    error_message TEXT,
    duration_ms INTEGER,
    updated_at TIMESTAMP,
    input_preview TEXT,
    cancel_requested_at TIMESTAMP,
    status VARCHAR(32),
    stop_reason VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS idx_agent_executions_agent ON agent_executions (agent_name);
CREATE INDEX IF NOT EXISTS idx_agent_executions_started ON agent_executions (started_at, id);
-- idx_agent_executions_trace (trace_id) 由 V2 迁移创建（同上，021）。

-- agent_run_events：流式工作台 append-only 活动事件（每 Run 单调 sequence）
CREATE TABLE IF NOT EXISTS agent_run_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id INTEGER NOT NULL,
    sequence INTEGER NOT NULL,
    type VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    payload_json TEXT NOT NULL,
    UNIQUE (run_id, sequence)
);
CREATE INDEX IF NOT EXISTS idx_agent_run_events_run_seq ON agent_run_events (run_id, sequence);

-- memory_entries：长期记忆条目（SqliteMemoryStore 后端，22 节）
-- scope=CORE 全量注入不截断；scope=ARCHIVAL 归档只带最近 N 条（查询 LIMIT，非删除）
-- agent_name（015 FR-014）：修复 sqlite 档作用域缺口，记忆跟 Agent 走；存量行由
-- V3 迁移幂等补列并归 '__global__' 占位（与 markdown 档全局回退语义对齐）
CREATE TABLE IF NOT EXISTS memory_entries (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    agent_name VARCHAR(128) NOT NULL DEFAULT '__global__',
    scope VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_memory_scope ON memory_entries (scope);
-- idx_memory_agent (agent_name, scope) 由 V3 迁移创建：存量库跑到本脚本时还没有 agent_name 列，
-- 在这里建索引会让整个应用启动失败（SC-003 教训）。

-- memory_vectors：归档记忆条目的向量索引（015）——派生数据，可从记忆本体全量重建，删了不伤本体。
-- entry_hash = sha256(agent|scope|条目原文)，跨后端档统一寻址；embedding 为 float32[] 小端序 BLOB
--（复用 014 编解码）。仅归档（ARCHIVAL）条目产生行——core 不参与检索故无需 scope 列（FR-005）；
-- DELEGATED 档（mem0）不产生行。entry_time 为条目时间（时间新近路依据，解析不出为 NULL 按最旧处理）。
CREATE TABLE IF NOT EXISTS memory_vectors (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    entry_hash VARCHAR(64) NOT NULL,
    agent_name VARCHAR(128) NOT NULL,
    content TEXT NOT NULL,
    embedding BLOB NOT NULL,
    dim INTEGER NOT NULL,
    embedding_model VARCHAR(128) NOT NULL,
    entry_time TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (agent_name, entry_hash)
);
CREATE INDEX IF NOT EXISTS idx_memvec_agent ON memory_vectors (agent_name);

-- notify_channels：全局通知渠道注册表（31 节）——name → type + url + config + 描述；管理台可 CRUD、Agent 按名字引用
-- （notify 工具的 channel 参数）。config 为类型相关多字段的 JSON 文本（如 email 的 host/port/from/to）。
-- 新表，CREATE TABLE IF NOT EXISTS，非 ALTER，无迁移风险。
CREATE TABLE IF NOT EXISTS notify_channels (
    name VARCHAR(128) PRIMARY KEY,
    type VARCHAR(32) NOT NULL,
    url TEXT NOT NULL,
    config TEXT,
    description TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- providers：LLM Provider 动态注册表（31 节）——name → api_key + base_url + 描述；管理台可 CRUD、运行时按名动态建 ChatModel。
-- 启动时仅把 config/application.yml 中数据库尚不存在且有效的 Provider 作为首次种子；
-- 已有同名记录绝不从 YAML 覆盖，之后以本表为唯一运行时事实源。
-- api_key 密文落库（022，enc:v1: 前缀，AES-256-GCM）：主密钥两档（ORYXOS_MASTER_KEY 环境变量优先，
-- 缺省 .oryxos/master.key 首启自动生成）——"核心阶段明文让步"已收回，详见部署文档「主密钥」节。
CREATE TABLE IF NOT EXISTS providers (
    name VARCHAR(128) PRIMARY KEY,
    api_key TEXT,
    base_url TEXT,
    description TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- llm_pricing：模型定价（016 审计看板）——(provider, model) → 输入/输出 token 单价（元/百万 token）。
-- 成本写时定格：LLM 调用落库时按 (provider, model) 查价算 cost_micros，历史成本不随改价变动。
-- 新表，CREATE TABLE IF NOT EXISTS，非 ALTER，无迁移风险。prompt_price/completion_price 可空=未定价。
CREATE TABLE IF NOT EXISTS llm_pricing (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    prompt_price REAL,
    completion_price REAL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE (provider, model)
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

-- web_users：管理台 Basic Auth 账号（012-web-auth）
-- 密码哈希存储（{bcrypt} 前缀 + hash），绝不存明文（宪法 VI 凭证不落地）
CREATE TABLE IF NOT EXISTS web_users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_web_users_username ON web_users (username);

-- web_sessions：浏览器登录 session（012-web-auth US3）
-- session_id = UUID（cookie 值）；expires_at = created_at + session-ttl（默认 12h）
-- 惰性清：filter 查到过期行顺手 delete，无后台定时线程
CREATE TABLE IF NOT EXISTS web_sessions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id VARCHAR(64) NOT NULL UNIQUE,
    username VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_web_sessions_session ON web_sessions (session_id);

-- knowledge_documents：知识文档索引状态（014 知识库）——库内源文件的派生数据，可从文件系统全量重建。
-- 状态机 PENDING → INDEXING → READY / FAILED（Clarify-Q3 两段式上传）；generation 为双缓冲代号（FR-024）：
-- 重建以 generation+1 写新代，旧代持续服务检索，就绪后原子切换并清理旧代。
CREATE TABLE IF NOT EXISTS knowledge_documents (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    kb_name VARCHAR(128) NOT NULL,
    rel_path VARCHAR(512) NOT NULL,
    content_sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    failure_reason TEXT,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    generation INTEGER NOT NULL DEFAULT 0,
    indexed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE (kb_name, rel_path, generation)
);
CREATE INDEX IF NOT EXISTS idx_kdoc_kb ON knowledge_documents (kb_name, generation);

-- knowledge_chunks：知识片段（检索最小单元）——embedding 为 float32[] BLOB（小端序），检索按库全量加载
-- 做纯 Java 余弦暴力扫描（research D1）；dim + embedding_model 支撑维度/模型一致性校验（FR-014）：
-- 与当前配置不一致时拒绝新旧向量混合比较并提示重建。page_no 仅 PDF 文档有值（出处用页码，FR-003）。
CREATE TABLE IF NOT EXISTS knowledge_chunks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    document_id INTEGER NOT NULL,
    kb_name VARCHAR(128) NOT NULL,
    seq INTEGER NOT NULL,
    page_no INTEGER,
    content TEXT NOT NULL,
    embedding BLOB,
    dim INTEGER,
    embedding_model VARCHAR(128),
    generation INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_kchunk_kb ON knowledge_chunks (kb_name, generation);
CREATE INDEX IF NOT EXISTS idx_kchunk_doc ON knowledge_chunks (document_id);

-- api_keys：REST API 机器调用凭证（018-rest-api-key）
-- 只存 SHA-256 哈希绝不存明文（宪法 VI）；key_prefix = 明文头部片段（oryx_ + 前 8 位随机），供盘点/日志对账
-- revoked_at 非空即失效（吊销即时生效，无缓存窗口）；key_hash UNIQUE 自带隐式索引，校验按此列查找
-- 新表，CREATE TABLE IF NOT EXISTS，非 ALTER，存量库无迁移风险
CREATE TABLE IF NOT EXISTS api_keys (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name VARCHAR(64) NOT NULL UNIQUE,
    key_prefix VARCHAR(16) NOT NULL,
    key_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL,
    last_used_at TIMESTAMP,
    revoked_at TIMESTAMP
);

-- tool_policy_rules：工具策略（020-tool-policy）——平台管理员的治理层，独立于 AGENT.md 的 tools: 声明。
-- rule_type：GLOBAL_DENY（全局禁用，agent_name 为空）/ AGENT_EXEMPT（指定 Agent 豁免全局 deny）/
--            AGENT_DENY（指定 Agent 额外收紧）。pattern 为工具精确名或 MCP server 通配（server:*）。
-- created_by 记录规则来源（管理台账号 / API 调用方），满足「配置即责任」最低追溯口径。
-- 新表，CREATE TABLE IF NOT EXISTS，非 ALTER，存量库无迁移风险。
CREATE TABLE IF NOT EXISTS tool_policy_rules (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    rule_type VARCHAR(16) NOT NULL,
    agent_name VARCHAR(255),
    pattern VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    created_by VARCHAR(255),
    UNIQUE (rule_type, agent_name, pattern)
);
