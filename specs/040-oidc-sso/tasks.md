# Tasks: OIDC/SSO 登录与企业身份映射

**Input**: Design documents from `/specs/040-oidc-sso/`

**Prerequisites**: plan.md、spec.md（US1~US3、Clarifications C1-A/C2-A/C3-C）、research.md（R1~R17）、data-model.md、contracts/oidc-auth-contract.md、quickstart.md

**Tests**: 包含——本刀是安全特性：协议校验矩阵（五类错误）、CAS 单次消费、R14 行为表、默认关零回归均属必测路径（039 先例）。

**Organization**: US1（PKCE 登录全流程）为 MVP；US2（身份映射与条件权威）与 US1 同 P1 但可在映射服务地基上独立验收；US3（认证审计）叠加收尾。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）

## Path Conventions

Maven 多模块单体，涉及 oryxos-storage / oryxos-web / oryxos-boot 三个既有模块（oryxos-core 零变化，见 plan.md）。

---

## Phase 1: Setup（表结构与依赖先行）

- [X] T001 新建 `oryxos-storage/.../migration/OidcSsoMigration.java`（`super("10", "oidc sso")`；建 `oidc_identities` / `auth_events` / `oidc_auth_requests` 三表与索引，DDL 见 data-model.md；镜像 `WebUserRolesMigration` 写法）
- [X] T002 [P] 新建 `oryxos-storage/src/main/resources/db/migration/postgresql/V10__oidc_sso.sql`（同号等价 DDL，`TIMESTAMPTZ`/IDENTITY 与 V9 同型；文件头注明与 SQLite JavaMigration 对齐）
- [X] T003 `SqliteMigrationsConfiguration.java` 注册 V10 Bean（依赖 T001）
- [X] T004 [P] 根 `pom.xml` dependencyManagement 钉 `com.nimbusds:nimbus-jose-jwt:10.3`；`oryxos-web/pom.xml` 引用（版本已实证，见 research R1）

**Checkpoint**: `MigrationEvolutionIT`/`LegacyTakeoverIT` 全绿；PG 侧 flyway history 健康

---

## Phase 2: Foundational（存储服务与协议纯函数）

**⚠️ CRITICAL**: 本阶段完成前不开始用户故事接线

- [X] T005 [P] `oryxos-storage/.../OidcAuthRequestEntity.java` + `OidcAuthRequestRepository.java`（`@Modifying` 原子 UPDATE 消费：`consumed_at IS NULL AND expires_at > ?`）+ `OidcAuthRequestStore.java`（create 生成 state/nonce/verifier、consume 返回 Optional、惰性清理过期行）
- [X] T006 [P] `oryxos-storage/.../OidcIdentity.java` + `OidcIdentityRepository.java`（`findByIssuerAndSubject`/`findByUsername`）
- [X] T007 [P] `oryxos-storage/.../AuthEvent.java` + `AuthEventRepository.java`（按时间/用户/subject 查询，无 delete）+ `AuthEventRecorder.java`（三纪律克隆 `AuthzEventRecorder`：MDC traceId、字段截断、异常只 ERROR）
- [X] T008 `oryxos-storage/.../OidcIdentityService.java`（依赖 T006）：`resolveOrProvision(iss, sub, email, derivedUsername, rolesFromClaims)` —— 命中更新 `last_login_at` + R14 角色刷新落库（返回角色是否变化）；未命中同事务建 `web_users` 行（随机弃置 bcrypt，R5）+ 映射行；返回 `{username, roles, firstLogin, rolesChanged}`
- [X] T009 [P] `oryxos-web/.../config/WebOidcProperties.java`（`oryxos.web.oidc.*` 12 键，默认值见 data-model §6）+ `WebAuthConfig` 注册
- [X] T010 [P] `oryxos-web/.../security/oidc/OidcUsernameDeriver.java`：preferred_username → email local-part → `oidc-`+sub 截断；小写化/非法字符清洗/长度 64 裁剪；冲突探测回调加 `-2/-3` 后缀
- [X] T011 [P] `oryxos-web/.../security/oidc/OidcRoleResolver.java`：R14 条件权威纯函数（输入 claim 值集合/映射配置/是否首登/本地角色 → 输出目标角色或「保留」）
- [X] T012 [P] `oryxos-web/.../security/oidc/JwksCache.java`：15min 缓存 + 未知 kid 限速强刷（60s）+ 失败沿用旧缓存（R11）
- [X] T013 `oryxos-web/.../security/oidc/OidcClient.java`（依赖 T009）：discovery 惰性拉取缓存、authorize URL 组装（PKCE S256）、code 换 token（`client_secret_post`）；RestClient + 超时工厂照抄 `ProviderModelsService` 模式
- [X] T014 `oryxos-web/.../security/oidc/IdTokenValidator.java`（依赖 T012）：JWS 验签（nimbus）+ iss/aud/exp/iat（±clock-skew）/nonce 校验；失败映射到 contracts 错误分类
- [X] T015 [P] 单测：`OidcAuthRequestStoreTest`（CAS 单次/重放/过期/清理）、`OidcIdentityServiceTest`（JIT/稳定映射/R14 五行）、`AuthEventRecorderTest`（截断/不上抛）
- [X] T016 [P] 单测：`OidcUsernameDeriverTest`、`OidcRoleResolverTest`（R14 全行）、`JwksCacheTest`（限速/旧缓存）、`IdTokenValidatorTest`（五类失败+偏移窗边界）

**Checkpoint**: `mvn -q -pl oryxos-storage,oryxos-web test` 全绿

---

## Phase 3: US1 — 企业用户经 IdP 单点登录管理台（P1，MVP）

- [X] T017 `oryxos-web/.../controller/OidcAuthController.java`（依赖 Phase 2 全部）：`GET /api/v1/auth/oidc/login`（302 IdP；未启用 404；discovery 失败 → `?error=idp_unreachable`）+ `GET /api/v1/auth/oidc/callback`（contracts 七步处理序，任一步失败 302 `?error=<分类>`；成功 `WebSessionService.create` + 既有 cookie 语义 302 `/admin/`）
- [X] T018 [P] ~~`AuthMeView` 增加 `oidcEnabled`~~ → 实现改为新端点 `GET /api/v1/auth/login-options`（登录页处于未认证态，`/me` 401 无 data 塞不进标志；`/me` 保持零变化——见 acceptance-report「与 plan 的差异」#2）
- [X] T019 [P] `oryxos-web/.../security/oidc/OidcStartupCheck.java`：必填缺失/`auth.enabled=false` 即拒（点名缺失项）；`rbac off`、非 https 非回环 WARN；不碰网络（R12）
- [X] T020 [P] `LoginView.vue`：`oidcEnabled` 显示「企业账号登录」按钮（跳 `/api/v1/auth/oidc/login`）；读取 `?error=` 分类映射中文提示；本地表单原样保留（C2-A）
- [X] T021 单测：`OidcAuthControllerTest`（MockMvc：错误分类矩阵全路径、成功建会话置 cookie、重放→invalid_state、未启用 404）、`OidcStartupCheckTest`（组合矩阵）
- [X] T022 前端构建验证（`npm run build` 于 src/main/frontend）+ 手工走查登录页双入口渲染

**Checkpoint**: US1 独立可验收——mock IdP 下全流程/五类错误路径可跑通

---

## Phase 4: US2 — 身份映射与条件权威（P1）

- [X] T023 callback 内接线映射链路（依赖 T008/T010/T011/T017）：claims 提取（roles-claim 可配）→ `OidcRoleResolver` → `OidcIdentityService.resolveOrProvision`；确保「同 sub 恒同用户」「首登派生唯一用户名」「命中刷新/未命中保留」
- [X] T024 [P] boot 层映射断言并入 `OidcSsoIT` 骨架：三档 claim 各登录一次 → `/api` 面 RBAC 放行/拒绝与 `authz_events` 落库（SC-003 两刀衔接闭环）
- [X] T025 [P] R14 行为表进用户文档（docs/OidcSsoGuide.md 初稿，含 Keycloak/Azure AD claim 配置示例）

**Checkpoint**: SC-003/SC-004 可断言

---

## Phase 5: US3 — 认证事件全程留审计（P2）

- [X] T026 OIDC 侧接线（依赖 T007/T017/T023）：login_success/login_failure（带分类与 source_ip）/mapping_created（仅首登）/roles_changed（仅变化）
- [X] T027 [P] 本地侧接线：`AuthApiController.login`（成功/失败）与 `logout` 各落一条（`auth_method=local`；039 G4 整体闭环）
- [X] T028 [P] RP-Initiated Logout（R15，默认关）：`rp-initiated-logout=true` 时 logout 响应附 `idpLogoutUrl`；LoginView/App.vue 拿到即跳转
- [X] T029 单测：审计触发点矩阵（重复登录不记 mapping、变化才记 roles_changed、写失败不阻断登录）

**Checkpoint**: US3 审计场景 1~3 可断言

---

## Phase 6: Polish & 门禁

- [X] T030 `oryxos-boot/src/test/java/io/oryxos/boot/OidcSsoIT.java` 完整化：内嵌 mock IdP（discovery/jwks/authorize/token 四端点，测试 RSA 签发）——全流程成功、五类协议错误注入、双入口并存、登出、审计断言、`oidc.enabled=false` 默认档零回归；命名 `*IT` 进 #453 integration-tests job
- [X] T031 [P] `config/application.yml.example` 增 `oryxos.web.oidc.*` 注释段（含启动校验组合与 R14 行为表引用）
- [X] T032 [P] CLAUDE.md 配置加载规则段补 OIDC 一句话口径；docs/OidcSsoGuide.md 定稿；website 中英文档成对更新
- [X] T033 全量门禁：`mvn verify`（Spotless/P3C/Checkstyle/SpotBugs/OWASP——新依赖 nimbus 过 CVE 扫描）+ `OidcSsoIT` 通过
- [X] T034 acceptance-report.md 落卷（对照 SC-001~006 与 quickstart V1~V12）

**Checkpoint**: 全部 SC 达成，CI 门禁绿
