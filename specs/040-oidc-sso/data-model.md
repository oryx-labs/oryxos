# Data Model: OIDC/SSO（Phase 1）

**Feature**: 040-oidc-sso | **Migration**: V10 双 vendor（PG `V10__oidc_sso.sql` / SQLite `OidcSsoMigration` JavaMigration，V9 先例同款）

## 1. oidc_identities — 外部身份 ↔ OryxOS 用户映射

稳定映射锚点（FR-004）：同 `issuer+subject` 恒对应同一 `username`；用户名只在首登派生一次，此后 IdP 侧改名/改邮箱不影响映射。

| 列 | 类型 | 约束 | 说明 |
|----|------|------|------|
| `id` | BIGINT | PK | 主键 |
| `issuer` | VARCHAR(255) | NOT NULL | IdP issuer（ID Token `iss` 原文） |
| `subject` | VARCHAR(255) | NOT NULL | IdP `sub`（稳定锚点） |
| `username` | VARCHAR(64) | NOT NULL | 对应 `web_users.username`（逻辑外键，不建 FK 约束——与既有表间惯例一致） |
| `email` | VARCHAR(255) | NULL | 展示/辅助排查用，不参与身份判定 |
| `first_login_at` | TIMESTAMP | NOT NULL | 首次登录（=JIT 供给时刻） |
| `last_login_at` | TIMESTAMP | NOT NULL | 最近登录 |

索引：`UNIQUE (issuer, subject)`（映射唯一性）；`idx_oidc_identities_username (username)`（按用户反查外部身份）。

## 2. auth_events — 认证事件审计（本地 + OIDC 双面收口）

`AuthzEventRecorder` 同族形态（截断/MDC traceId/写失败只 ERROR 不上抛）；只增不删，Repository 不提供 delete。

| 列 | 类型 | 约束 | 说明 |
|----|------|------|------|
| `id` | BIGINT | PK | 主键 |
| `event_type` | VARCHAR(32) | NOT NULL | `login_success` / `login_failure` / `logout` / `mapping_created` / `roles_changed` |
| `auth_method` | VARCHAR(16) | NOT NULL | `local` / `oidc` |
| `username` | VARCHAR(64) | NULL | 映射后的 OryxOS 用户（失败时可空） |
| `external_issuer` | VARCHAR(255) | NULL | OIDC 事件的 IdP issuer |
| `external_subject` | VARCHAR(255) | NULL | OIDC 事件的 `sub`（跨登录审计串联，SC-004） |
| `roles` | VARCHAR(255) | NULL | 本次生效角色快照（CSV，`serializeRoles` 同序） |
| `failure_reason` | VARCHAR(64) | NULL | 失败分类枚举（见 contracts §错误分类），**绝不含令牌内容** |
| `source_ip` | VARCHAR(64) | NULL | 来源 IP（forward-headers 解析后） |
| `trace_id` | VARCHAR(64) | NULL | MDC 取值，与四观测面同源 |
| `created_at` | TIMESTAMP | NOT NULL | 事件时间 |

索引：`idx_auth_events_created_at (created_at)`；`idx_auth_events_subject (external_subject)`；`idx_auth_events_username (username)`。

## 3. oidc_auth_requests — 授权流程临时状态（TTL 10 分钟）

多副本共享事实源（FR-008/SC-005）：发起与回调可落不同副本。消费走 CAS：`UPDATE ... SET consumed_at = ? WHERE state = ? AND consumed_at IS NULL AND expires_at > ?`，更新行数 0 = 重放/过期，拒绝（Edge「回调重放」）。

| 列 | 类型 | 约束 | 说明 |
|----|------|------|------|
| `state` | VARCHAR(64) | PK | 随机 state（URL-safe base64，≥256bit 熵） |
| `nonce` | VARCHAR(64) | NOT NULL | 与 ID Token `nonce` claim 比对 |
| `pkce_verifier` | VARCHAR(128) | NOT NULL | PKCE code_verifier 原文（S256 挑战已发 IdP） |
| `created_at` | TIMESTAMP | NOT NULL | 创建时间 |
| `expires_at` | TIMESTAMP | NOT NULL | 创建 +10min |
| `consumed_at` | TIMESTAMP | NULL | 消费标记（CAS 目标列） |

清理：惰性——每次 `create`/`consume` 顺手 `DELETE WHERE expires_at < now`（`WebSessionService` 先例），零新增调度件。

## 4. 既有表变化

- **`web_users`**：**零 schema 变化**。JIT 供给只是新增行：`password_hash` = 随机 256-bit 秘密的 bcrypt（生成即弃，本地登录路径正常走且永不匹配）；`roles` 按 R14 行为表写入。
- **`web_sessions`**：零变化，OIDC 会话与本地会话同构同表。
- **`authz_events`**：零变化（授权拒绝与认证事件分族分表）。

## 5. 实体/服务（oryxos-storage）

| 类 | 性质 | 说明 |
|----|------|------|
| `OidcIdentity` + `OidcIdentityRepository` | 新增 | `findByIssuerAndSubject` / `findByUsername` |
| `AuthEvent` + `AuthEventRepository` | 新增 | 按时间/用户/subject 查询，无 delete |
| `AuthEventRecorder` | 新增 | 落库封装，三纪律克隆 `AuthzEventRecorder` |
| `OidcAuthRequestEntity` + repository | 新增 | CAS 消费用 `@Modifying` 原子 UPDATE |
| `OidcIdentityService` | 新增 | 映射查询、JIT 供给（映射行+用户行同事务）、`last_login_at` 更新、R14 角色刷新落库 |
| `WebUserService` | 复用 | `rolesOf` / `setRoles` / 建用户路径不改签名 |

## 6. 配置（`oryxos.web.oidc.*`，WebOidcProperties）

| 键 | 默认 | 说明 |
|----|------|------|
| `enabled` | `false` | 总开关（默认关=现状零变化，SC-002） |
| `issuer` | — | IdP issuer URL（Discovery 基址），开启时必填 |
| `client-id` / `client-secret` | — | 开启时必填；secret 推荐 `${OIDC_CLIENT_SECRET}` |
| `redirect-base-url` | — | 对外可达基址，回调=`{base}/api/v1/auth/oidc/callback`，开启时必填 |
| `scopes` | `openid,profile,email` | 授权请求 scope |
| `roles-claim` | （空=不启用映射） | 承载角色的 claim 名（如 `groups`） |
| `role-mappings` | （空） | claim 值 → VIEWER/EDITOR/ADMIN 映射表 |
| `provision-default-roles` | `VIEWER` | JIT 首登无命中时的默认角色 |
| `clock-skew` | `60s` | ID Token 时间类校验偏移窗 |
| `rp-initiated-logout` | `false` | 登出联动 IdP 端 |
| `connect-timeout` / `read-timeout` | `5s` / `10s` | 出站 HTTP 超时（discovery/token/JWKS 共用） |
