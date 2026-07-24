# Data Model: Web Service 认证机制（最小 Auth）

**Feature**: 012-web-auth | **Date**: 2026-07-22

依据 spec Key Entities + FR-003/FR-009/FR-010 + research §1/§2。

## 实体：WebUser

管理员账号。一个 WebUser = 一个可登录管理台的账号。

### 关系图

```
WebUser (web_users 表, SQLite)
  └─ 无外键关联其他实体（独立账号表，不连 Session/Agent/审计表）
```

`web_users` 不关联现有 `sessions`/`llm_calls`/`tool_invocations`/`scheduled_tasks`/`memory_entries`。auth 是横切关注点，账号表独立。

### 字段

| 字段（Java） | 列名 | 类型 | 约束 | 说明 |
|---|---|---|---|---|
| `id` | `id` | `Long` | PK，`GENERATIONTYPE.IDENTITY`，auto-increment | surrogate key，非业务键 |
| `username` | `username` | `String` | `NOT NULL UNIQUE`，1-64 字符，无空格 | 登录名，业务唯一键 |
| `passwordHash` | `password_hash` | `String` | `NOT NULL`，`{bcrypt}` 前缀 + hash | BCrypt 哈希，**非明文**（宪法 VI） |
| `enabled` | `enabled` | `boolean` | `NOT NULL DEFAULT TRUE` | 禁用账号登录 401（US1 场景 6） |
| `createdAt` | `created_at` | `Instant` | `NOT NULL` | `@PrePersist` 设 `Instant.now()`，无 setter（不可变） |
| `updatedAt` | `updated_at` | `Instant` | `NOT NULL` | service 每次改动手动 `setUpdatedAt(Instant.now())` |

### DDL（追加进 `oryxos-storage/src/main/resources/schema.sql`）

```sql
CREATE TABLE IF NOT EXISTS web_users (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,   -- {bcrypt} 前缀 + hash
    enabled       BOOLEAN NOT NULL DEFAULT 1,
    created_at    TIMESTAMP NOT NULL,
    updated_at    TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_web_users_username ON web_users (username);
```

镜像现有 `llm_calls`/`sessions` 块风格（`INTEGER PRIMARY KEY AUTOINCREMENT` + `TIMESTAMP` + `BOOLEAN NOT NULL` + `CREATE INDEX IF NOT EXISTS`）。

### 验证规则（FR-011，在 `WebUserService` / CLI 落实）

- username：非空、无空格、≤64 字符
- rawPassword：≥8 字符、非空
- 两次输入一致（`add`/`passwd`）
- 重名拒绝（`existsByUsername`）

### 状态转换

```
[新建] --add--> enabled=TRUE --disable--> enabled=FALSE --enable(后续)--> TRUE
                                          \--delete--> [删除行]
```

核心阶段只做 `disable`（置 false），不做 `enable` 子命令（spec US2 无 enable，YAGNI）。删 = 整行删。

## 配置实体：WebAuthProperties

非持久化，`@ConfigurationProperties(prefix = "oryxos.web.auth")`。

| 字段 | YAML 键 | 类型 | 默认 | 说明 |
|---|---|---|---|---|
| `enabled` | `oryxos.web.auth.enabled` | `boolean` | `false` | 总开关，关=现状裸内网（SC-001） |
| `realm` | `oryxos.web.auth.realm` | `String` | `"OryxOS"` | Basic Auth realm 文案（curl 401 时 `WWW-Authenticate` 用） |
| `sessionTtl` | `oryxos.web.auth.session-ttl` | `Duration` | `12h` | session 过期时间（`web_sessions.expires_at` = `created_at + ttl`） |

无 `exclude-paths`（`/admin/**` scope 已天然排除 `/api/v1/**`，`/api/v1/health` 不在 `/admin/**` 内自动免认证，`/admin/login` 在 filter 内显式放行）。

## 实体：WebSession

浏览器登录 session。一个 WebSession = 一次登录会话。

### 关系图

```
WebSession (web_sessions 表, SQLite)
  └─ username → WebUser.username（逻辑关联，不建外键——删用户不级联清 session，
                                      核心阶段简化；扩展阶段可加级联或级联校验）
```

### 字段

| 字段（Java） | 列名 | 类型 | 约束 | 说明 |
|---|---|---|---|---|
| `id` | `id` | `Long` | PK，`GenerationType.IDENTITY`，auto-increment | surrogate key |
| `sessionId` | `session_id` | `String` | `NOT NULL UNIQUE`，UUID | cookie 值，安全随机生成 |
| `username` | `username` | `String` | `NOT NULL` | 关联 `web_users.username` |
| `expiresAt` | `expires_at` | `Instant` | `NOT NULL` | 过期时间 = `createdAt + sessionTtl` |
| `createdAt` | `created_at` | `Instant` | `NOT NULL` | `@PrePersist` 设 `Instant.now()` |

### DDL（追加进 `oryxos-storage/src/main/resources/schema.sql`）

```sql
CREATE TABLE IF NOT EXISTS web_sessions (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id  VARCHAR(64) NOT NULL UNIQUE,
    username    VARCHAR(64) NOT NULL,
    expires_at  TIMESTAMP NOT NULL,
    created_at  TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_web_sessions_session ON web_sessions (session_id);
```

### 验证规则

- `session_id` 由 `java.security.SecureRandom` 或 `UUID.randomUUID` 生成（不可预测）
- `expires_at` = `createdAt + sessionTtl`，filter 查到 `expires_at < now` 视为无效

### 状态转换

```
[登录成功] --create--> 有效(expires_at>now) --过期/登出--> [删除行]
```

核心阶段不做 session 续期（滑动续期留扩展阶段）。登出 = 删行。过期 = filter 当无效。

## 契约实体：登录凭据

非持久化。`POST /api/v1/auth/login` 接 JSON `{username, password}`，返 200 + `Set-Cookie: oryxos_session=<uuid>; HttpOnly; SameSite=Strict; Secure(HTTPS); Path=/`。后续请求浏览器自动带 cookie。

## 契约实体：BasicAuth 凭据

非持久化，运行时 HTTP 头 `Authorization: Basic <Base64(user:pass)>`。filter 拆解 → `WebUserService.verify(user, pass)` → 200 或 401。curl/自动化路径，与 session 并存。无独立存储。
