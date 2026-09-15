# Implementation Plan: OIDC/SSO 登录与企业身份映射

**Branch**: `040-oidc-sso` | **Date**: 2026-09-15 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/040-oidc-sso/spec.md`（3 项澄清已拍板：C1-A JIT 供给 / C2-A 并存 / C3-C 条件权威）

## Summary

在既有自研双 Filter 体系内实现标准 OIDC 授权码 + PKCE 登录：`oryxos-web` 新增自研 `OidcClient`（RestClient + 显式超时，惰性 Discovery + JWKS 限速缓存）与两个 `/api/v1/auth/oidc/*` 端点（天然落在既有豁免前缀内，豁免清单零改动）；ID Token 验签引 `nimbus-jose-jwt:10.3`（已实证 Central 构件存在、零强制传递依赖）。身份映射以 `iss+sub` 锚定新表 `oidc_identities`，未映射用户 JIT 自动供给进既有 `web_users`（随机弃置 bcrypt 密码，本地账密门对 OIDC 用户永不可通），角色按**条件权威**行为表（R14）刷新；会话直接复用 `web_sessions` 落库形态——OIDC 登录只是既有会话的另一种建立方式，多副本天然可用。state/nonce/PKCE 临时状态落新表 `oidc_auth_requests`，CAS 单次消费（026 纪律），发起与回调可落不同副本。认证事件（本地+OIDC 双面）落新表 `auth_events`（`AuthzEventRecorder` 三纪律克隆），闭环 039 移交的认证审计项。迁移 V10 双 vendor。零破坏锚点：`oryxos.web.oidc.enabled` 默认 `false`，关闭档行为与交付前逐字节一致；本地账密并存不隐藏（IdP 宕机不锁死）；`Principal`/#462 契约零变化（Kind 不扩）。

## Technical Context

**Language/Version**: Java 21（虚拟线程，同现状）

**Primary Dependencies**: Spring Boot 3.x（MVC + RestClient）、Spring Data JPA、**新增 `com.nimbusds:nimbus-jose-jwt:10.3`**（根 pom dependencyManagement 钉版，oryxos-web 引用；BC/tink 全 optional，零强制传递依赖，2026-09-15 已 curl 实证）；不引 Spring Security / spring-security-oauth2

**Storage**: Flyway V10 双 vendor——新表 `oidc_identities` / `auth_events` / `oidc_auth_requests`；PG `V10__oidc_sso.sql`，SQLite `OidcSsoMigration`（JavaMigration，V9 `WebUserRolesMigration` 先例）；`web_users`/`web_sessions`/`authz_events` 零 schema 变化

**Testing**: JUnit 5——storage（CAS 单次消费、JIT 供给事务、R14 行为表、AuthEventRecorder 纪律）、web（claims 校验偏移窗、用户名派生冲突、JWKS kid 限速、控制器错误分类矩阵）、boot `OidcSsoIT`（**内嵌 mock IdP** 四端点，五类协议错误注入，进 #453 integration-tests job）；Keycloak 真 IdP 走查见 quickstart

**Target Platform**: Linux server（单 fat JAR / 容器，同现状）

**Project Type**: Maven 多模块单体——涉及 oryxos-web（OIDC 客户端/端点/启动校验/前端）、oryxos-storage（三表+实体+服务+V10）、oryxos-boot（IT）；oryxos-core **零变化**

**Performance Goals**: 登录回调链路（不含 IdP 网络）p95 < 200ms；JWKS 缓存命中时验签纯计算；每登录 ≤ 4 次 DB 写（state 消费/映射/会话/审计）

**Constraints**: 默认关零破坏（SC-002）；出站 HTTP 全部显式超时（ProviderModelsService 模式）；令牌内容零出现于日志/审计/响应（SC-006）；同步阻塞（宪法 VII）；审计写失败不阻断主链路（FR-007）

**Scale/Scope**: 单 IdP；新表 3 张、新端点 2 个、配置键 ~12 个；约 18 个新文件（含测试）+ 6 个既有文件小改

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 评估 | 结论 |
|------|------|------|
| I 自实现 ReAct 循环 | 不涉及：不触运行时执行路径 | ✅ |
| II Spring AI 边界 | 不涉及 | ✅ |
| III Provider 显式映射 | 不涉及 | ✅ |
| IV 目录=Agent / Skill 软连接 | 不涉及 | ✅ |
| V 审计 Day One 落库 | 强化：认证事件新表 `auth_events` 本地+OIDC 双面 Day One 写入；既有审计表零变化 | ✅ |
| VI 安全是地基 / 不用 SecurityManager | 本刀即安全面：PKCE 恒开、state/nonce 双校验 + CAS 单次消费、JWKS 验签、错误分类不泄密、JIT 最低角色 + 「OIDC 开 RBAC 关」启动 WARN、client-secret 走环境变量、OIDC 用户本地密码门物理不可通 | ✅ |
| VII 同步执行 + 虚拟线程 | 回调处理同步阻塞（IdP 网络 IO 由虚拟线程吸收）；无 Reactor/CompletableFuture | ✅ |
| VIII 状态外置 / Flyway 迁移 | 临时授权状态与映射全落库（多副本正确性不依赖进程内存）；V10 双 vendor 同号只增不改 | ✅ |
| 模块约束 | 零新模块；OIDC 属 Web 认证面落 oryxos-web，持久化落 oryxos-storage，core 契约零变化；无循环依赖；唯一新依赖 nimbus-jose-jwt 按「复用管道」原则（签名验证=管道非核心） | ✅ |
| 012/#462 既定边界 | 不引 Spring Security 全套；授权决策点/矩阵/Filter 结构不动 | ✅ |

**Phase 1 设计后复评**: 通过。需显式声明的裁决：新依赖引入（R1，已实证存在性与依赖树）；BasicAuthFilter G1 缺口刻意不搭车（R13，039 差额单独跟进）。无需 Complexity Tracking。

## Project Structure

### Documentation (this feature)

```text
specs/040-oidc-sso/
├── spec.md              # 需求：US1~US3 / FR-001~FR-012 / SC-001~SC-006 / Clarifications（C1-A/C2-A/C3-C）
├── plan.md              # 本文件
├── research.md          # Phase 0：R1~R17 技术裁决
├── data-model.md        # Phase 1：三新表 + V10 双轨 + 配置键
├── quickstart.md        # Phase 1：Keycloak 走查 V1~V12
├── contracts/
│   └── oidc-auth-contract.md  # Phase 1：端点/错误分类/审计/兼容/多副本契约
├── tasks.md             # Phase 2（/speckit-tasks 产出）
└── checklists/requirements.md
```

### Source Code (repository root)

```text
pom.xml                                        # 修改：dependencyManagement 钉 nimbus-jose-jwt:10.3

oryxos-storage/
├── src/main/resources/db/migration/postgresql/V10__oidc_sso.sql   # 新增：三表+索引
├── src/main/java/io/oryxos/storage/migration/
│   ├── OidcSsoMigration.java                  # 新增：SQLite V10（V9 先例同款）
│   └── SqliteMigrationsConfiguration.java     # 修改：注册 V10 Bean
├── src/main/java/io/oryxos/storage/
│   ├── OidcIdentity.java / OidcIdentityRepository.java      # 新增：iss+sub 映射
│   ├── OidcAuthRequestEntity.java / OidcAuthRequestRepository.java  # 新增：CAS @Modifying 消费
│   ├── OidcIdentityService.java               # 新增：映射/JIT 供给（同事务）/R14 角色刷新落库
│   ├── AuthEvent.java / AuthEventRepository.java            # 新增：认证事件（无 delete）
│   └── AuthEventRecorder.java                 # 新增：三纪律克隆 AuthzEventRecorder
└── src/test/java/io/oryxos/storage/
    ├── OidcAuthRequestStoreTest.java          # 新增：CAS 单次消费/过期/惰性清理
    ├── OidcIdentityServiceTest.java           # 新增：JIT/稳定映射/R14 行为表全行
    └── AuthEventRecorderTest.java             # 新增：字段截断/失败不上抛

oryxos-web/
├── pom.xml                                    # 修改：引 nimbus-jose-jwt
├── src/main/java/io/oryxos/web/config/
│   ├── WebOidcProperties.java                 # 新增：oryxos.web.oidc.*（12 键，enabled 默认 false）
│   └── WebAuthConfig.java                     # 修改：注册 WebOidcProperties
├── src/main/java/io/oryxos/web/security/oidc/
│   ├── OidcClient.java                        # 新增：discovery 惰性缓存 + code 换 token（RestClient 超时工厂）
│   ├── JwksCache.java                         # 新增：15min 缓存 + kid 未知限速强刷（60s）
│   ├── IdTokenValidator.java                  # 新增：JWS 验签 + iss/aud/exp/iat/nonce（±clock-skew）
│   ├── OidcRoleResolver.java                  # 新增：R14 条件权威纯函数
│   ├── OidcUsernameDeriver.java               # 新增：preferred_username→email→sub 派生 + 冲突后缀
│   └── OidcStartupCheck.java                  # 新增：必填项 fail-fast / rbac-off WARN / 非 https WARN（不碰网络）
├── src/main/java/io/oryxos/web/controller/
│   ├── OidcAuthController.java                # 新增：GET oidc/login（302 IdP）+ GET oidc/callback（处理序 7 步）
│   ├── AuthApiController.java                 # 修改：login/logout 接 AuthEventRecorder；logout 可选 idpLogoutUrl
│   └── dto/AuthMeView.java                    # 修改：+oidcEnabled（既有字段零变化）
├── src/main/frontend/src/views/LoginView.vue  # 修改：企业登录按钮（oidcEnabled 显隐）+ ?error= 分类中文提示
└── src/test/java/io/oryxos/web/security/oidc/
    ├── IdTokenValidatorTest.java              # 新增：五类失败 + 偏移窗边界
    ├── OidcRoleResolverTest.java              # 新增：R14 五行为全覆盖
    ├── OidcUsernameDeriverTest.java           # 新增：派生优先级/冲突/非法字符清洗
    └── JwksCacheTest.java                     # 新增：缓存/kid 强刷限速/失败沿用旧缓存

oryxos-boot/src/test/java/io/oryxos/boot/
└── OidcSsoIT.java                             # 新增：内嵌 mock IdP 全流程 + 五类协议错误 + 默认档零回归 + 审计断言

config/application.yml.example                 # 修改：oryxos.web.oidc.* 注释段（含 R14 行为表引用）
CLAUDE.md                                      # 修改：配置加载规则段补 OIDC 一句话口径
docs/WebConsoleGuide 或 docs/新 OidcSsoGuide.md # 修改/新增：R14 行为表 + Keycloak/Azure AD 配置示例
website/docs/*.md 与 website/zh/docs/*.md       # 修改：认证说明成对更新
```

**Structure Decision**: OIDC 全部落 `oryxos-web`（协议客户端/验签/端点/启动校验）与 `oryxos-storage`（三表/服务/审计），`oryxos-core` 零变化——OIDC 是管理台认证面的扩展，不是跨模块契约；`Principal`/`AuthorizationService` 原样复用即是 #462「统一主体」设计的验证。`web/security/oidc/` 子包收纳全部协议件，与既有 `security/` 平级件（Filter/Enforcer）隔离，删除/禁用 OIDC 不触碰任何既有类。

## 关键设计裁决（详见 research.md R1~R17）

| # | 裁决 | 要点 |
|---|------|------|
| R1 | nimbus-jose-jwt 10.3 | 构件已实证；零强制传递依赖；只用纯函数面，HTTP 自管 |
| R2 | 自研 OidcClient + RestClient 超时纪律 | 惰性 discovery（启动不碰网络，IdP 宕机不影响 boot 与本地登录） |
| R3 | 临时状态落库 CAS 单次消费 | `oidc_auth_requests` TTL 10min 惰性清理；重放天然拒绝；多副本正确 |
| R4 | 会话零新机制 | `WebSessionService`/cookie 原样复用，OIDC 会话同构 |
| R5 | JIT 供给（C1-A） | `iss+sub` 锚点；用户名首登派生一次；随机弃置 bcrypt 封死本地密码门 |
| R6 | `auth_events` 双面收口 | 本地+OIDC 登录/登出/映射全落；039 G4 整体闭环；与 `authz_events` 分族分表 |
| R7 | 配置走 yaml | IdP 参数=部署期配置；secret 走环境变量；管理台可编辑留后续 |
| R8 | 端点落 `/api/v1/auth/oidc/*` | 既有豁免前缀内，豁免清单/skip 表零改动 |
| R9 | 前端零新路由设施 | LoginView 加按钮 + error 分类提示；App.vue 状态机不动 |
| R10 | Kind 不扩 | OIDC 用户=Kind.USER；来源区分由映射表+审计承担；#462 契约零变化 |
| R11 | JWKS 限速缓存 | 15min + kid 强刷 60s 限速；失败沿用旧缓存 |
| R12 | OidcStartupCheck | 必填缺失/auth-off 即拒；rbac-off、非 https 仅 WARN；不碰网络 |
| R13 | G1（/admin 主体）不搭车 | 静态面无数据可保护；039 差额单独跟进 |
| R14 | 条件权威行为表（C3-C） | 命中→刷新；未配置/未命中→保留本地；表原样进用户文档 |
| R15 | RP-Initiated Logout 可选 | 默认关响应逐字节一致；开=响应附 idpLogoutUrl 前端跳转 |
| R16 | V10 双 vendor | PG SQL + SQLite JavaMigration（V9 先例） |
| R17 | 内嵌 mock IdP 测试 | 五类协议错误可注入；`OidcSsoIT` 进 #453 CI job；Keycloak 留人工走查 |
