# Quickstart: OIDC/SSO 验收走查（Phase 1）

**Feature**: 040-oidc-sso。自动化验收以 `OidcSsoIT`（内嵌 mock IdP，五类协议错误可注入）为准；本文是真 IdP（Keycloak）人工走查路径。

## 准备：本地 Keycloak

```bash
docker run -d --name oryxos-kc -p 8081:8080 \
  -e KEYCLOAK_ADMIN=admin -e KEYCLOAK_ADMIN_PASSWORD=admin \
  quay.io/keycloak/keycloak:26.0 start-dev
```

Keycloak 控制台（http://localhost:8081）：建 realm `oryxos-demo` → 建 client `oryxos-admin`（confidential，开启 Standard Flow + PKCE S256，Redirect URI `http://localhost:8080/api/v1/auth/oidc/callback`）→ 建组 `oryxos-admins` 并配置 groups claim 进 ID Token → 建两个测试用户（一个入组、一个不入组）。

## 配置 OryxOS

```yaml
oryxos:
  web:
    auth:
      enabled: true          # 前置：oryxos user add 已建本地 ADMIN
    rbac:
      enabled: true
    oidc:
      enabled: true
      issuer: http://localhost:8081/realms/oryxos-demo
      client-id: oryxos-admin
      client-secret: ${OIDC_CLIENT_SECRET}
      redirect-base-url: http://localhost:8080
      roles-claim: groups
      role-mappings:
        "/oryxos-admins": ADMIN
```

## 走查项（映射 SC）

| # | 步骤 | 预期 | SC |
|---|------|------|-----|
| V1 | 打开 `/admin/`，登录页应同时有本地表单 + 「企业账号登录」 | 双入口并存（C2-A） | FR-010/011 |
| V2 | 点企业登录 → Keycloak 认证（入组用户）→ 回跳 | 进入管理台，身份为 JIT 供给用户，角色 ADMIN | SC-001/003 |
| V3 | 不入组用户登录 | JIT 供给成功，角色 VIEWER（默认档）；写操作被 403 且落 `authz_events` | SC-003 |
| V4 | 同一用户重复登录 N 次 | `oidc_identities` 恒一行、`last_login_at` 更新；无重复 mapping 事件 | SC-004 |
| V5 | Keycloak 把用户移出 `oryxos-admins`（配置了映射→IdP 权威） | 下次登录角色刷新降档，落 `roles_changed` | US2/R14 |
| V6 | 回调 URL 手工篡改 state / 重放同一回调 | 302 登录页 `?error=invalid_state`，不建会话，落 `login_failure` | SC-001 |
| V7 | 登出后回退访问受保护页 | 需重新登录；`logout` 事件在 `auth_events` | FR-006 |
| V8 | 停掉 Keycloak 容器 | 企业登录报 `idp_unreachable`；**本地账密登录照常**（不锁死） | Edge/C2-A |
| V9 | 查 `auth_events` | V2~V8 各场景恰好对应事件、失败带分类、无令牌内容 | SC-006/US3 |
| V10 | `oidc.enabled=false` 重启 | 登录页无企业入口，全量既有测试绿 | SC-002 |
| V11 | 双副本（026 集群档 + PG）：副本 A 发起、杀 A 后回调落 B | 登录照常成功 | SC-005 |
| V12 | 配置缺 `client-id` 启动 | 启动即拒，报错点名缺失项 | FR-002 |
