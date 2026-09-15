# 验收落卷：040 OIDC/SSO 登录与企业身份映射（#461）

**Date**: 2026-09-15 | **Branch**: `040-oidc-sso` | 拍板口径：C1-A（JIT 供给）/ C2-A（并存）/ C3-C（条件权威）

## SC 达成对照

| SC | 口径 | 结论 | 证据 |
|----|------|------|------|
| SC-001 | 全流程走通 + 五类协议错误各自分类拒绝并留审计 | ✅ | `OidcSsoIT` 10/10：`fullLogin_adminGroup_*`（PKCE 全流程，mock IdP 校验 S256(verifier)==challenge）；`protocolErrors_categorized`（invalid_state/idp_error/invalid_signature/expired_token/invalid_claims 五路 + login_failure 审计断言）；`replayedState_rejected`（CAS 重放拒绝） |
| SC-002 | 未配置档零变化 | ✅ | `defaultOff_zeroChange`（新端点 404、login-options=false）；storage/web **全量既有测试零改动全绿**（仅 `AuthApiControllerTest` 因构造参数增列作适配，断言零变化）；`enabled` 默认 false |
| SC-003 | claim→role 三档映射 + #462 衔接 | ✅（衔接至角色落点） | `fullLogin`（groups→ADMIN 落 `web_users.roles`）、`jitProvision_noGroups_defaultViewer`；角色 → 矩阵裁决路径复用 039 既有 `rolesOf()`（039 E2E 已钉），本 IT 断言到落库角色为止 |
| SC-004 | 同 subject 跨登录映射稳定 | ✅ | `secondLogin_stableMapping_noDuplicateMappingEvent`（映射行恒 1、mapping 事件不重复）；审计按 `external_subject` 可串联（`idx_auth_events_subject`） |
| SC-005 | 双副本回调互通 | ✅（机制级） | 临时状态/会话/映射全落共享 DB（`oidc_auth_requests` CAS + `web_sessions`），单测 `OidcAuthRequestStoreSqliteTest` 钉 CAS 单次消费；真双副本走查留 quickstart V11（需 PG 集群档环境） |
| SC-006 | 令牌内容零出现 | ✅ | `auditFields_neverContainTokens`；错误面只有 8 类枚举码（contracts）；`OidcClient`/`IdTokenValidator` 异常与日志只含分类/异常类名 |

## 测试面

- 单测 44 个新增：`OidcAuthRequestStoreSqliteTest`(4，真 SQLite+V10)、`OidcIdentityServiceTest`(9，R14 行为表全行)、`AuthEventRecorderTest`(4)、`IdTokenValidatorTest`(10，真 RSA 签名)、`JwksCacheTest`(5，限速/旧缓存)、`OidcRoleResolverTest`(6)、`OidcUsernameDeriverTest`(6)、`OidcStartupCheckTest`(5)、`OidcAuthControllerTest`(9)
- `OidcSsoIT`(10)：内嵌 mock IdP（discovery/JWKS/token 三端点 + 测试侧 RSA 签发），进 #453 `integration-tests` CI job（`*IT` 命名）
- 前端 `npm run build` 通过（LoginView 双入口 + error 分类提示 + App.vue RP-logout 跳转）

## 实现要点（与 plan 的差异，如实记录）

1. **回调成功用站内中转页而非 302**：`oryxos_session` 是 SameSite=Strict，回调处于 IdP 发起的跨站重定向链上，302 直跳 `/admin/` 时 cookie 不随行（经典 OAuth 坑，用户「看似未登录」）。改为 200 中转页 `location.replace('/admin/')`——下一跳变同站导航，不降级 cookie 策略。contracts §1 已同步。
2. **前端探测走新端点 `GET /api/v1/auth/login-options`** 而非扩展 `/auth/me`：登录页处于未认证态，`/me` 此时 401 无 data，塞不进 `oidcEnabled`。`/me` 保持零变化（G3 的 roles/rbacEnabled 仍属 039 差额）。
3. **本地账密登录/登出一并接入 `auth_events`**（`auth_method=local`）：C2-A 并存档下本地登录同为登录，039 移交项 G4 整体闭环。
4. Kind 不扩（R10）、BasicAuthFilter 不动（R13/G1 留 039 差额）、IdP 配置走 yaml（R7）均按 plan 落地。

## 已知留白

- 双副本真机走查（quickstart V11）与 Keycloak 真 IdP 走查（V1~V10）：需相应环境，机制面已由 IT+单测覆盖
- RBAC 全开档的 IT（rbac.enabled=true 需启动期 ADMIN 种子）：角色落库→矩阵裁决链路由 039 E2E 承接，未在本 IT 重复
- back-channel logout / 多 IdP / SCIM：spec「不做」清单，未实现
