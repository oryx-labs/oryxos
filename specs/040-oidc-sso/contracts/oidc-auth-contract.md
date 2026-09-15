# Contract: OIDC 认证流程与审计（Phase 1）

**Feature**: 040-oidc-sso

## 1. 端点契约

两个新端点均在既有豁免前缀 `/api/v1/auth/` 之下——ApiKeyAuthFilter 豁免清单、RequestActionResolver skip 表**零改动**。

### GET /api/v1/auth/oidc/login

发起授权码 + PKCE 登录。

- **前置**：`oidc.enabled=true` 且 discovery 可用。
- **行为**：生成 `state`（≥256bit）、`nonce`、PKCE `code_verifier`/S256 挑战 → 落 `oidc_auth_requests` → `302` 到 IdP authorize 端点（`response_type=code`、`scope`、`state`、`nonce`、`code_challenge`、`code_challenge_method=S256`、`redirect_uri={base}/api/v1/auth/oidc/callback`）。
- **失败**：`oidc.enabled=false` → 404；discovery 不可达 → `302 /admin/login?error=idp_unreachable`。

### GET /api/v1/auth/oidc/callback?code=...&state=...

处理 IdP 回调，成败均以 302 收尾（浏览器上下文，不返回 JSON 错误体）。

处理序（任一步失败即短路 → `302 /admin/login?error=<分类>` + `login_failure` 审计）：

1. IdP 报错参数（`?error=`）→ 分类 `idp_error`
2. `state` CAS 消费（单次；重放/过期/未知 → `invalid_state`）
3. code 换 token（`client_secret_post` + `code_verifier`；HTTP 失败/非 200 → `token_exchange_failed`）
4. ID Token JWS 验签（JWKS，kid 轮换限速强刷；失败 → `invalid_signature`）
5. Claims 校验：`iss` 精确匹配、`aud` 含 client_id、`exp`/`iat`（±clock-skew）、`nonce` 匹配 → `invalid_claims` / `expired_token`
6. 映射/JIT 供给（R5/R14；建用户失败 → `provisioning_failed`）
7. `WebSessionService.create(username)` + `Set-Cookie: oryxos_session=...`（HttpOnly/SameSite=Strict/Secure-when-https）→ **200 站内中转页**（`location.replace('/admin/')`）——回调处于 IdP 发起的跨站重定向链上，`SameSite=Strict` cookie 不随跨站链后续跳转发送（302 直跳会「看似未登录」）；中转页把下一跳变为同站导航，不降级 cookie 策略

### 错误分类枚举（`?error=` 与 `auth_events.failure_reason` 共用）

`idp_unreachable` / `idp_error` / `invalid_state` / `token_exchange_failed` / `invalid_signature` / `invalid_claims` / `expired_token` / `provisioning_failed`

**保密承诺（FR-005/FR-009/SC-006）**：分类枚举是对外错误的全部信息量；令牌原文、IdP 响应体、堆栈不出现在响应/日志/审计任何面。

### GET /api/v1/auth/me（扩展）

响应增加字段 `oidcEnabled: boolean`（前端显隐企业登录入口依据）。既有字段与语义零变化；`roles`/`rbacEnabled` 属 039 差额（G3），不在本刀。

### POST /api/v1/auth/logout（扩展）

既有行为零变化（删 session 行 + 清 cookie，幂等）。`rp-initiated-logout=true` 且本会话为 OIDC 来源时，响应 data 附 `idpLogoutUrl`（end_session_endpoint + `post_logout_redirect_uri`），前端自行跳转；默认关时响应逐字节一致。

## 2. 审计契约（US3）

| 触发点 | event_type | auth_method | 必填字段 |
|--------|-----------|-------------|---------|
| OIDC 回调全通过 | `login_success` | `oidc` | username, external_issuer, external_subject, roles, source_ip |
| OIDC 回调任一步失败 | `login_failure` | `oidc` | failure_reason, source_ip（subject 仅在已解出时填） |
| 本地账密登录成功/失败 | `login_success`/`login_failure` | `local` | username（失败=尝试名）, source_ip |
| 登出（两种来源） | `logout` | `local`/`oidc` | username |
| JIT 首登供给 | `mapping_created` | `oidc` | username, external_issuer, external_subject, roles |
| R14 刷新致角色变化 | `roles_changed` | `oidc` | username, roles（新值） |

- 稳定映射的重复登录**不**记 mapping 事件（噪音控制，US3 场景 3）。
- 写入失败只 ERROR 日志，绝不阻断登录主链路（FR-007）。

## 3. 兼容性承诺（SC-002 锚点）

| 面 | 承诺 |
|----|------|
| `oidc.enabled=false`（默认） | 全部行为与本刀交付前逐字节一致：无新端点注册之外的任何路径变化、`/auth/me` 仅多一个恒 `false` 字段、登录页无企业入口 |
| 本地账密 Basic Auth | 并存（C2-A）：表单、`/auth/login`、锁定策略、启动校验全部零变化 |
| API Key 面 | 零变化：豁免清单、验证路径、RBAC 上限不动 |
| `Principal` 契约（#462） | 零变化：Kind 不扩，OIDC 用户 = `Kind.USER` + `rolesOf()` 既有路径 |
| 审计既有三表 + `authz_events` | 零变化 |
| 迁移 | V10 双 vendor，只增不改；V1~V9 不动 |
| 会话 | `web_sessions`/cookie 语义零变化，OIDC 会话同构不可区分地接受授权判定 |

## 4. 多副本契约（SC-005）

- `oidc_auth_requests` 为共享 DB 事实源：发起落副本 A、回调落副本 B 流程照常。
- state 消费为 CAS 原子操作：并发/重放恰好一个成功。
- JWKS/discovery 缓存为副本本地缓存（正确性不依赖：miss 即重拉），无跨副本失效需求。
