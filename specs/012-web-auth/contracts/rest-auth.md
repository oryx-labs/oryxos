# REST Contract: `/api/v1/auth/*`

**Feature**: 012-web-auth | **Date**: 2026-07-22

浏览器登录页配套的 session 认证端点。归 `/api/v1/auth/**` 子树（REST API 不受 `/admin/**` filter 限制，但 auth 端点本身是认证专用）。复用 `web_users` 账号 + `WebUserService.verify`。

## 端点

### `POST /api/v1/auth/login`

登录，建 session，设 cookie。

- **请求体**: `{"username": "<user>", "password": "<pass>"}`
- **成功** (200):
  - `Set-Cookie: oryxos_session=<uuid>; Path=/; HttpOnly; SameSite=Strict`（HTTPS 加 `Secure`）
  - body: `{"code":0,"message":"success","data":{"username":"<user>"},"timestamp":...}`（统一信封）
- **失败** (401): 账密错/用户不存在/禁用 → `{"code":401,"message":"Invalid username or password","data":null,...}`（不区分原因，防枚举）
- **不设 WWW-Authenticate**: 登录端点不挑 Basic（避免浏览器弹窗）

### `POST /api/v1/auth/logout`

登出，清当前 session + 清 cookie。

- **请求**: 无 body（从 cookie 取 session id）
- **成功** (200): `Set-Cookie: oryxos_session=; Path=/; Max-Age=0`（清 cookie）+ body `{"code":0,"message":"success",...}`
- **未登录** (200): 仍返 200（幂等，无 session 也算登出成功）

### `GET /api/v1/auth/me`

查当前登录用户。

- **已登录** (200): `{"code":0,"...","data":{"username":"<user>"}}`
- **未登录** (401): `{"code":401,"message":"Not authenticated",...}`

## filter 放行

`/api/v1/auth/login` 与 `/admin/login` 都在 filter 放行名单（未登录可访问）。`/api/v1/auth/logout`、`/api/v1/auth/me` 需有效 session（但 `/api/v1/**` 本就不被 `/admin/**` filter 拦，这两个端点靠 controller 自身校验 session）。

## cookie 属性

| 属性 | 值 | 理由 |
|---|---|---|
| `Name` | `oryxos_session` | |
| `Value` | UUID（session id） | 不可预测 |
| `Path` | `/` | 全域（含 `/admin/` + `/api/v1/auth/*`） |
| `HttpOnly` | 是 | 防 JS 偷（XSS） |
| `SameSite` | `Strict` | 防 CSRF |
| `Secure` | HTTPS 时加 | 公网防嗅探 |
| `Max-Age` | `sessionTtl`（12h 默认） | 过期时间 |

## 错误码

- 200 成功
- 401 未认证/账密错/禁用
- 400 请求体格式错（缺字段）
