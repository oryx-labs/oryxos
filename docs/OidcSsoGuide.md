# OIDC/SSO 企业登录指南（040，#461）

OryxOS 管理台支持标准 **OIDC 授权码 + PKCE** 登录：企业已有 IdP（Keycloak / Azure AD / Authing 等，凡支持
OIDC Discovery 的均可）的账号可直接登录管理台，外部身份自动映射为 OryxOS 用户与角色（#462 RBAC 体系）。
默认关闭，开启后**本地账密始终并存**（IdP 宕机不锁死管理员）。

## 1. 快速配置

```yaml
oryxos:
  web:
    auth:
      enabled: true            # 前置：先 oryxos user add 建本地 ADMIN
    rbac:
      enabled: true            # 强烈建议：关闭时任何 IdP 用户登录即获全功能（启动会 WARN）
    oidc:
      enabled: true
      issuer: https://idp.example.com/realms/main
      client-id: oryxos-admin
      client-secret: ${OIDC_CLIENT_SECRET}     # 环境变量注入，勿明文
      redirect-base-url: https://oryxos.example.com
      roles-claim: groups
      role-mappings:
        "/oryxos-admins": ADMIN
        "/oryxos-editors": EDITOR
```

IdP 侧注册 confidential client，回调地址填 `{redirect-base-url}/api/v1/auth/oidc/callback`，开启
Authorization Code + PKCE（S256）。OryxOS 只依赖 Discovery（`/.well-known/openid-configuration`）、
授权码换 token、JWKS 验签三个标准面，无任何 IdP 私有扩展。

四个必填项（issuer / client-id / client-secret / redirect-base-url）缺一启动即拒并点名；
`auth.enabled=false` 时开 OIDC 同样启动即拒（OIDC 产出的就是管理台会话）。启动校验不碰网络——
IdP 宕机不影响 OryxOS 启动与本地登录。

## 2. 身份映射（谁进来、变成谁）

- **锚点 = `iss + sub`**：同一外部身份恒映射同一 OryxOS 用户（`oidc_identities` 表）；IdP 侧改名、
  改邮箱不影响映射。email 仅作展示。
- **JIT 自动供给**：IdP 认证通过但 OryxOS 无对应用户时，首登自动建用户——用户名按
  `preferred_username` → email 本地部 → `oidc-<sub>` 派生（冲突加 `-2` 后缀，仅首登派生一次）。
  JIT 用户没有可用的本地密码（随机弃置哈希），无法走本地账密门。
- **默认角色**：首登无 claim 命中时给 `provision-default-roles`（缺省最低档 VIEWER）。

## 3. 角色权威（条件权威行为表）

| 场景 | role-mappings 配置 | claim 命中 | 行为 |
|------|-------------------|-----------|------|
| 首登（JIT） | 任意 | 有命中 | 角色 = 命中集 |
| 首登（JIT） | 任意 | 无命中 | 角色 = `provision-default-roles`（缺省 VIEWER） |
| 再次登录 | 已配置 | 有命中 | 角色刷新为命中集（**IdP 权威，撤组即降权**），变化落 `roles_changed` 审计 |
| 再次登录 | 已配置 | 无命中 | **保留本地角色**（防 IdP 组维护疏漏锁死全员） |
| 再次登录 | 未配置 | — | 保留本地角色（**本地权威**，`oryxos user role` 有效） |

一句话：**配置了映射规则，就是声明「角色归 IdP 集中治理」；没配，角色归本地管。**

## 4. 登出与会话

- 会话与本地登录同构（`web_sessions` + `oryxos_session` HttpOnly cookie），时长沿
  `oryxos.web.auth.session-ttl`（默认 12h）；不追随 IdP token 过期。
- IdP 侧禁用/删除账号：已有会话有效至过期，之后无法再登录（本刀不做 back-channel 即时吊销）。
- `rp-initiated-logout: true` 时，管理台登出会联动跳转 IdP 端登出（`end_session_endpoint`）。

## 5. 审计（auth_events 表）

登录成功/失败（含 8 类协议错误分类）、登出、首登映射（`mapping_created`）、角色变化
（`roles_changed`）全部落库，含外部 subject（跨登录串联）、来源 IP、traceId；**令牌内容零落库零日志**。
本地账密登录/登出同表同族记录（`auth_method` 区分 `local`/`oidc`）。

## 6. 故障与安全口径

- **IdP 宕机**：企业登录入口报可读错误；本地账密照常（并存设计）；已建会话不受影响。
- **回调防护**：state CAS 单次消费（重放必拒）、nonce 防令牌重放、PKCE S256 恒开启、JWKS 验签
  含 kid 轮换容错（15min 缓存 + 60s 限速强刷）；多副本部署（026）下发起与回调可落不同副本。
- **错误不泄密**：登录页只出现分类错误码（`invalid_state` / `expired_token` 等 8 类），IdP 响应体与
  令牌内容不出现在任何响应、日志、审计中。
- 生产环境 `redirect-base-url` 必须 https（非回环明文 http 启动 WARN）；反向代理终止 TLS 时配
  `server.forward-headers-strategy: framework`。

## 7. 本地试用（Keycloak）

见 `specs/040-oidc-sso/quickstart.md`——docker 起 Keycloak → 建 realm/client/组 → 按上文配置，
V1~V12 走查表覆盖全部验收口径。
