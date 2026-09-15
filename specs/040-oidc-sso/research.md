# Research: OIDC/SSO 登录与企业身份映射（Phase 0）

**Feature**: 040-oidc-sso | **Date**: 2026-09-15

前置事实（代码走查，HEAD d08fbc6）：认证为自研双 Filter（无 Spring Security，仅 `spring-security-crypto`）；管理台会话为落库 `web_sessions` + `oryxos_session` HttpOnly cookie（非 servlet HttpSession）；`web_users.roles`（V9）+ `WebUserService.rolesOf()` 每请求重读已就位；RBAC 只接线在 ApiKeyAuthFilter（`/api/**` 含 session 互认路径）；全仓零 JWT/JOSE 依赖；`authz_events` 只记授权拒绝、schema 不适配认证事件。

## R1 JOSE 库选型：nimbus-jose-jwt 10.3

**裁决**：引入 `com.nimbusds:nimbus-jose-jwt:10.3`（版本钉根 pom dependencyManagement，依赖挂 oryxos-web）。

- **存在性已实证**（2026-09-15 curl Central）：`nimbus-jose-jwt-10.3.jar` 及 sources/javadoc 齐全；pom 中 BouncyCastle 三件与 tink 全部 `optional=true`——**零强制传递依赖**。RS256/ES256 验签走 JDK 内置 JCA，不引 BC。
- **为什么不是** `spring-boot-starter-oauth2-client`：拖入 Spring Security 全套过滤链，违反 012/#462 既定边界（宪法级）。
- **为什么不是** jose4j：能力等价，但 nimbus 是 OIDC 生态事实标准（Keycloak/Connect2id 同源），JWKSet/JWTClaimsSet API 更完整。
- **为什么不自研 JWS 验证**：签名验证是典型「管道」非「核心循环」，自研密码学校验违反安全第一原则。
- **只用其纯函数面**：`SignedJWT.parse` / `JWSVerifier` / `JWKSet.parse` / `IDTokenClaimsVerifier` 语义自实现；**不用**其 `RemoteJWKSet`/`DefaultJWTProcessor` 的自带 HTTP 取回（超时纪律要收在自己手里，见 R11）。

## R2 OIDC 客户端落位与 HTTP 形态

**裁决**：自研 `OidcClient` 落 `oryxos-web`（`web/security/oidc/`），出站 HTTP 用 Spring `RestClient` + 显式超时工厂——照抄 `ProviderModelsService` 已验证模式（`JdkClientHttpRequestFactory` + connect/read timeout，默认 5s/10s 可调）。

- oryxos-core 无 `spring-boot-starter-web`，`RestClient` 不能落 core；OIDC 纯属 Web 认证面，不进 core 契约。
- Discovery（`/.well-known/openid-configuration`）**惰性拉取 + 缓存**，不在启动期拉：C2-A 并存档要求 IdP 宕机不影响本地登录与进程启动；启动校验只验配置完整性不碰网络（R12）。Discovery 失败时登录入口返回可读错误（`idp_unreachable`），下次点击重试。
- token 端点认证用 `client_secret_post`（兼容面最广）；PKCE S256 恒开启（issue 验收硬要求）。

## R3 state/nonce/PKCE 临时状态：落库 CAS 单次消费

**裁决**：新表 `oidc_auth_requests`（state 为主键，存 nonce、PKCE verifier、创建/过期时间、消费标记），TTL 10 分钟，惰性清理（仿 `WebSessionService.findValid` 顺手删过期行）。回调消费走**原子 UPDATE ... WHERE consumed_at IS NULL**（026 CAS 纪律）——同 state 重放第二次必然失败，天然满足「授权码/state 单次消费」。

- **为什么不是进程内 Map**：登录发起与回调可落不同副本（SC-005），026 明令进程内状态不承载正确性。
- **为什么不是加密 cookie（无状态）**：需自管加密密钥与防重放（cookie 可重放），复杂度高于一张 TTL 表；且 verifier 留服务端更不暴露。
- 单机 SQLite 档同一张表零新增运维件（FR-008）。

## R4 会话：零新机制，复用 WebSessionService

**裁决**：OIDC 回调验证通过后直接 `WebSessionService.create(username)` + 既有 `buildCookie` 语义（HttpOnly/SameSite=Strict/Secure-when-https）。会话时长沿 `oryxos.web.auth.session-ttl`（默认 12h），不追随 IdP token 过期（spec Edge 已定）。登出复用既有 `/api/v1/auth/logout` 删行+清 cookie；RP-Initiated Logout 见 R15。

## R5 身份映射与 JIT 供给（C1-A）

**裁决**：新表 `oidc_identities`（`issuer + subject` 唯一键 ↔ `username`，含 email 展示列与首次/最近登录时间）。登录时：

1. 查 `(iss, sub)` 命中 → 既有用户，走 R14 角色刷新；
2. 未命中 → **JIT 供给**：派生用户名（`preferred_username` → email local-part → `sub` 前缀截断，冲突加 `-2/-3` 后缀；仅首登派生一次，此后恒走映射表，改名/改邮箱不影响锚点）、建 `web_users` 行、建映射行、落 `mapping_created` 审计。
- JIT 用户的 `password_hash`（NOT NULL 约束）= **随机 256-bit 秘密的 bcrypt 哈希，生成后即弃**——`verify()` 走正常路径且永不可能匹配，OIDC 用户无法走本地账密门；不用未知前缀哨兵值（DelegatingPasswordEncoder 对未知 id 抛异常，会把登录尝试变 500）。
- 供给默认角色 `provision-default-roles`（缺省 VIEWER）。

## R6 认证事件审计：新表 auth_events，双面收口

**裁决**：新表 `auth_events`（V10）+ `AuthEventRecorder`（storage，克隆 `AuthzEventRecorder` 三纪律：MDC 取 traceId、字段截断、异常只 ERROR 绝不上抛阻断主链路）。事件类型：`login_success` / `login_failure` / `logout` / `mapping_created` / `roles_changed`，带 `auth_method`（`local`/`oidc`）、外部 issuer/subject、角色快照、失败分类、来源 IP。

- **本地账密登录/登出一并接入**（AuthApiController 两处各加一行 record）：C2-A 并存档下本地登录同为登录，且这是 039 移交项 G4 的整体闭环——单独为 OIDC 记而漏本地会留下审计盲区。
- 高频放行噪音控制：稳定映射的重复登录**不**记 mapping 事件，仅 `roles_changed` 时记（US3 场景 3）。
- 为什么不复用 `authz_events`：语义两族（授权拒绝 vs 认证生命周期），列不适配（无 event_type/result/IP/IdP），硬塞会让两边查询都变脏。同族形态、分表存放。

## R7 配置形态：yaml 属性（首刀不落库）

**裁决**：`WebOidcProperties`，prefix `oryxos.web.oidc.*`：`enabled`（默认 false）、`issuer`、`client-id`、`client-secret`（推荐 `${OIDC_CLIENT_SECRET}` 环境变量）、`scopes`（默认 `openid,profile,email`）、`redirect-base-url`（对外可达基址，回调=`{base}/api/v1/auth/oidc/callback`）、`roles-claim`（如 `groups`）、`role-mappings`（claim 值→VIEWER/EDITOR/ADMIN）、`provision-default-roles`（默认 VIEWER）、`clock-skew`（默认 60s）、`rp-initiated-logout`（默认 false）、`connect/read-timeout`。

- IdP 参数是部署期基础设施配置（同 DB 连接），与 provider key（运行期可增删的业务资源）性质不同——首刀 yaml 即可，管理台可编辑落库（022 加密面）留后续按需。密钥不落库则本刀不触 022。

## R8 端点与豁免面

**裁决**：两个新端点，全部落在既有豁免前缀 `/api/v1/auth/` 之下——**ApiKeyAuthFilter 豁免清单与 RequestActionResolver skip 表零改动**：

- `GET /api/v1/auth/oidc/login`：生成 state/nonce/verifier 落库 → 302 到 IdP authorize 端点。
- `GET /api/v1/auth/oidc/callback`：state CAS 消费 → code 换 token（PKCE）→ ID Token 验签+claims 校验（iss/aud/exp/iat/nonce，偏移窗 R7）→ 映射/供给 → 建会话 → 302 `/admin/`；任何失败 302 `/admin/login?error=<分类>`（不泄露细节，FR-005）。
- `GET /api/v1/auth/me` 增加 `oidcEnabled` 字段（前端据此显隐企业登录入口）；roles/rbacEnabled 缺失是 039 差额（G3），不混入本刀。

## R9 前端接入

**裁决**：`LoginView.vue` 在 `oidcEnabled` 时显示「企业账号登录」按钮（`window.location.href = '/api/v1/auth/oidc/login'`）；本地账密表单**原样保留**（C2-A）。回调失败的 `?error=` 查询参数由 LoginView 读取映射为可读中文提示。登录成功 302 回 `/admin/` 后由既有 `App.vue checkAuth()` 状态机接管，前端零新增路由设施（现状无 vue-router，不引入）。

## R10 主体形态：Kind 不扩，零触 #462 契约

**裁决**：OIDC 登录产出的主体仍是 `Principal.Kind.USER`（session 互认路径已有：cookie→username→`rolesOf()`）；**不**给 Kind 加 OIDC 值。

- 授权语义上 OIDC 用户与本地用户就该无差别（FR-003「无差别路径」）；加 Kind 值会迫使 `RoleBasedAuthorizationServiceImpl` 矩阵分支膨胀且无任何裁决差异。
- 「区分来源」由 `oidc_identities` 映射表 + `auth_events.auth_method` 承担（审计/运维可查），不进请求热路径。

## R11 JWKS 缓存与 kid 轮换

**裁决**：自管 `JwksCache`：首次验签按 discovery 的 `jwks_uri` 拉取（RestClient 超时纪律），成功缓存 15 分钟；遇未知 `kid` 触发**限速强刷**（同一 uri 最小间隔 60s，防被伪造 kid 打成拉取风暴）——覆盖 IdP 密钥轮换窗口（FR-009）。拉取失败沿用旧缓存（可用性优先），无缓存则登录失败 `idp_unreachable`。

## R12 启动校验：OidcStartupCheck

**裁决**：`ApplicationRunner` + `@ConditionalOnWebApplication(SERVLET)`（三兄弟同款），`oidc.enabled=true` 时：

- **fail-fast 抛错**：`issuer`/`client-id`/`client-secret`/`redirect-base-url` 任一缺失（点名缺失项，FR-002）；`oryxos.web.auth.enabled=false`（OIDC 是管理台登录方式，认证关=无会话概念，误配组合启动即拒，沿 026 惯例）。
- **WARN**：`rbac.enabled=false`（点名「任何 IdP 用户登录后即获全功能」，FR-012）；`redirect-base-url` 非 https 且非回环（生产 https 要求，Edge 已定）。
- 不碰网络（R2）：IdP 可达性不是启动前置。

## R13 /admin 面主体置入（039 缺口 G1）：本刀不做

**裁决**：BasicAuthFilter 不动。`/admin/**` 只发静态 SPA 资源（HTML/JS/CSS），一切数据读写走 `/api/**`——RBAC 在 API 面已收口，静态面置主体无裁决可用（无数据可保护）。G1 作为 039 差额单独跟进，不搭车。

## R14 条件权威角色刷新（C3-C）行为表

**裁决**：登录成功后按下表执行（实现集中在一个 `OidcRoleResolver` 纯函数 + service 落库）：

| 场景 | role-mappings 配置 | claim 命中 | 行为 |
|------|-------------------|-----------|------|
| 首登（JIT） | 任意 | 有命中 | roles = 命中集，落 `mapping_created` |
| 首登（JIT） | 任意 | 无命中 | roles = `provision-default-roles`（缺省 VIEWER） |
| 再登 | 已配置 | 有命中 | roles := 命中集（IdP 权威，撤组即降权）；变化时落 `roles_changed` |
| 再登 | 已配置 | 无命中 | **保留本地角色**（不清空——防「IdP 组维护疏漏=全员锁死」） |
| 再登 | 未配置 | — | 保留本地角色（本地权威，`oryxos user role` 有效） |

此表原样进用户文档（C3 拍板要求「语义以文档行为表显式说明」）。

## R15 RP-Initiated Logout（可选，默认关）

**裁决**：`rp-initiated-logout=true` 时，`POST /api/v1/auth/logout` 响应体附 `idpLogoutUrl`（discovery `end_session_endpoint` + `post_logout_redirect_uri`），前端拿到即跳转；默认关时响应与现状逐字节一致。不做 back-channel logout（spec「不做」清单）。

## R16 迁移版本：V10 双 vendor

**裁决**：PG `V10__oidc_sso.sql`（`oidc_identities` + `auth_events` + `oidc_auth_requests` 三表+索引）；SQLite 走 `JavaMigration`（`OidcSsoMigration`，version "10"，注册进 `SqliteMigrationsConfiguration`——V9 先例同款）。只增不改、非幂等干净 SQL（V6+ 纪律）。

## R17 测试策略：内嵌 mock IdP

**裁决**：boot 层 E2E 用**测试内嵌 mock IdP**（一个测试用 HttpServer：discovery/jwks/authorize/token 四端点，nimbus 测试侧生成 RSA 密钥签发 ID Token）——协议错误五路（state/nonce/签名/过期/IdP 拒绝）全部可注入，命名 `OidcSsoIT` 进 #453 的 `integration-tests` CI job。Keycloak 容器留 quickstart 人工走查（不进 CI，避免外部镜像依赖）。单元层：state store CAS 单次消费、用户名派生与冲突、R14 行为表全行、claims 校验（时钟偏移窗）、JWKS kid 轮换限速。
