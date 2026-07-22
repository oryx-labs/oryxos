# Contract: 管理后台认证 API 与访问策略

所有端点沿用 `ApiResponse` 信封：

```json
{
  "code": 0,
  "message": "success",
  "data": {},
  "timestamp": 0
}
```

错误响应将 HTTP 状态映射到 `code`，`data` 为 `null`。任何响应不得返回密码、密码散列、CSRF token 之外的会话凭证或 Cookie 值。

## Public bootstrap endpoints

| Method | Path | Request | Success `data` | Errors | Notes |
|---|---|---|---|---|---|
| GET | `/api/v1/auth/status` | — | `{ configured, authenticated, username? }` | — | 只返回前端引导所需的布尔状态；未配置时提供不含敏感值的部署指引。 |
| GET | `/api/v1/auth/csrf` | — | `{ headerName, token }` | 503 | 创建/刷新当前浏览器的 CSRF 验证值；响应 `Cache-Control: no-store`。 |
| POST | `/api/v1/auth/login` | `{ "username": "...", "password": "..." }` + CSRF header | `{ "username": "..." }` | 400, 401, 403, 429, 503 | 成功后建立服务端会话并刷新 CSRF token。401 对未知账号与错误密码使用完全相同的通用文案。 |

### Login rules

- `username` 与 `password` 均为必填，空白或畸形请求返回 400 且不建立会话。
- 配置缺失或不合法时返回 503，受保护资源仍保持拒绝。
- 同一账号在 15 分钟内第五次失败后，随后的 15 分钟登录返回 429；未知账号也适用同一规则。
- 安全事件持久化失败时，登录返回 503 且不得建立认证会话。

## Authenticated endpoints

| Method | Path | Request | Success `data` | Errors | Notes |
|---|---|---|---|---|---|
| POST | `/api/v1/auth/logout` | CSRF header | `{ "loggedOut": true }` | 401, 403 | 记录退出事件、清除安全上下文、失效当前会话并删除会话 Cookie。 |
| GET | `/api/v1/auth/events?limit={1..100}` | Optional `limit`, default 50 | `AdminAuthEventView[]` | 400, 401 | 最新优先、数量有上限；只返回事件类型、时间、主体和已净化的来源元数据。 |

### `AdminAuthEventView`

```json
{
  "principal": "admin",
  "eventType": "LOGIN_SUCCEEDED",
  "occurredAt": "2026-07-22T09:00:00Z",
  "remoteAddress": "127.0.0.1",
  "userAgent": "Mozilla/5.0"
}
```

## Protected-resource policy

| Resource group | Policy |
|---|---|
| `/admin/**` | Static SPA/login shell may load anonymously, but it must not fetch or render protected management data until `auth/status` confirms an authenticated session. |
| `GET /api/v1/health`, authentication bootstrap endpoints | Public. |
| All other `/api/v1/**` routes | Require authenticated administrator. This includes Agent, workspace, session, schedule, profile, tool, memory, system-info and sandbox administration routes. |
| `/swagger-ui/**`, `/v3/api-docs/**`, non-health Actuator endpoints | Require authenticated administrator. |
| Health probe endpoints | Remain explicitly public for operational liveness/readiness probes. |

## Error behavior

| HTTP | `ApiResponse.message` intent | Trigger |
|---|---|---|
| 401 | Generic authentication required or invalid credentials | Missing/expired session; unknown username or wrong password. |
| 403 | Request verification failed | Missing/invalid CSRF or forbidden request. |
| 429 | Login temporarily unavailable; retry later | Failure-limit window active. |
| 503 | Administrator authentication is not configured / audit storage unavailable | Valid server-side configuration or persistence prerequisite missing. |

Security filter exceptions must use this JSON contract rather than Spring Security’s default HTML response. Frontend code treats 401 and session-expiry 403 as one transition back to the login screen; it surfaces other `message` values without raw stack traces.

## Explicitly out of scope

- Self-registration, account recovery, password change and multi-administrator management.
- Role/permission differentiation: every authenticated administrator has the single full management access scope.
- OAuth2/OIDC/SAML login, JWTs, cross-origin clients, remember-me authentication and distributed session storage.
