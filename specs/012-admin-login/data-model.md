# Data Model: 管理后台用户登录

## 1. AdminSecurityProperties（运行配置）

单一初始管理员的非持久化配置。配置缺失时 `configured=false`，所有受保护资源仍拒绝访问。

| Field | Source | Rules |
|---|---|---|
| `username` | `ORYXOS_ADMIN_USERNAME` | 必填；非空、去除首尾空白；仅用于认证与审计主体标识。 |
| `passwordHash` | `ORYXOS_ADMIN_PASSWORD_HASH` | 必填；必须是 `PasswordEncoder` 可识别的编码值；不得输出到日志、响应或数据库。 |
| `sessionIdleTimeout` | `ORYXOS_ADMIN_SESSION_IDLE_TIMEOUT`，默认 30 分钟 | 必须为正值；无活动到期后会话不再认证。 |
| `secureCookie` | `ORYXOS_SECURITY_COOKIE_SECURE` | 生产 HTTPS 必须为 `true`；本地 HTTP 验证可显式设为 `false`。 |

**Validation state**:

```text
missing/invalid username or passwordHash -> UNCONFIGURED
valid username and recognized passwordHash -> CONFIGURED
```

`UNCONFIGURED` 不是降级开放模式：登录状态端点可给出配置指引，其他受保护 API 一律拒绝。

## 2. LoginFailureState（进程内）

每个提交的账号一条线程安全状态，服务重启后清空；这符合当前单进程范围，不承诺跨实例或跨重启的失败限制。

| Field | Type | Rules |
|---|---|---|
| `usernameKey` | String | 规范化后的账号键；未知账号也维护状态，避免存在性侧信道。 |
| `windowStartedAt` | Instant | 当前 15 分钟失败窗口的起点。 |
| `failureCount` | int | 窗口内的连续失败次数，范围 0–5。 |
| `lockedUntil` | Instant, nullable | 第五次失败后为当前时间加 15 分钟。 |

**State transitions**:

```text
NO_STATE --first failure--> COLLECTING(1)
COLLECTING(1..3) --failure within window--> COLLECTING(n+1)
COLLECTING(4) --failure--> LOCKED(until = now + 15m)
LOCKED --attempt before lockedUntil--> LOCKED (reject, no session)
LOCKED --attempt after lockedUntil--> NO_STATE, then evaluate the new attempt
COLLECTING/LOCKED --successful authentication--> NO_STATE
COLLECTING --failure after window expiry--> COLLECTING(1, new window)
```

所有更新在同一账号键上原子执行；系统不返回该账号是否存在。

## 3. AdminAuthEvent（跨模块值对象）

`oryxos-core` 定义的不可变审计输入，由 Web 认证服务创建、由 Storage 落库。

| Field | Type | Rules |
|---|---|---|
| `principal` | String | 提交的账号标识；不得包含密码或散列。 |
| `eventType` | Enum | `LOGIN_SUCCEEDED`、`LOGIN_FAILED`、`LOGIN_LOCKED`、`LOGOUT`。 |
| `occurredAt` | Instant | 认证服务注入 `Clock` 生成，便于边界测试。 |
| `remoteAddress` | String, nullable | 直接连接地址；仅在受信任反向代理已配置时解析转发地址。 |
| `userAgent` | String, nullable | 截断到合理长度并移除控制字符。 |
| `sessionId` | String, nullable | 仅在已建立或已终止会话时记录；不是可重放凭证。 |

`AdminAuthAuditStore.record(AdminAuthEvent)` 是同步调用。登录成功必须在成功审计持久化后才建立认证会话；审计故障应明确返回服务不可用，不得声称已可追溯。

## 4. `admin_auth_events`（SQLite）

手工建表脚本新增以下持久化实体，遵循现有 `schema.sql` + JPA Entity/Repository 模式。

| Column | Type | Constraints / Purpose |
|---|---|---|
| `id` | INTEGER | Primary key, auto-increment. |
| `principal` | VARCHAR(255) | Not null; normalized submitted account identifier. |
| `event_type` | VARCHAR(32) | Not null; one of the four event types. |
| `remote_address` | VARCHAR(128) | Nullable; source address metadata. |
| `user_agent` | VARCHAR(512) | Nullable; sanitized/truncated source metadata. |
| `session_id` | VARCHAR(255) | Nullable; audit correlation only. |
| `created_at` | TIMESTAMP | Not null; indexed with event type for recent-event queries. |

No password, password hash, CSRF token, request body or session cookie column may be added. The protected audit query returns a bounded newest-first view and never exposes omitted sensitive data.

## 5. AdminSession（Servlet session）

The servlet container stores the authenticated admin principal in the Spring Security context. It is not a new JPA entity.

| Property | Rule |
|---|---|
| Authentication principal | The configured administrator username only. |
| Lifetime | Invalidated on logout or after 30 minutes of inactivity. |
| Cookie | `HttpOnly`, `SameSite=Strict`, production `Secure`; no custom bearer token. |
| CSRF linkage | State-changing requests require the current server-issued CSRF token. |

Future OIDC/SAML support replaces the local identity adapter while retaining this normalized principal, audit event model, protected-resource policy and admin UI session contract.
