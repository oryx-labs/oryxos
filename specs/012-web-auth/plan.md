# Implementation Plan: Web Service 认证机制（最小 Auth）

**Branch**: `012-web-auth` | **Date**: 2026-07-22 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/012-web-auth/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command. See `.specify/templates/plan-template.md` for the execution workflow.

## Summary

给 OryxOS 管理台（`/admin/**`）加最小认证，feature flag `oryxos.web.auth.enabled` 默认关，不破"假设内网"宪法前提。账号存 SQLite 新表 `web_users`，密码用 `spring-security-crypto` 的 `DelegatingPasswordEncoder`（`{bcrypt}` 前缀）哈希，绝不存明文。浏览器走 session/cookie + 登录页（`/admin/login`），curl/自动化走 Basic Auth，两者并存 filter 都认。session 存 SQLite 新表 `web_sessions`（过期 12h 可配，惰性清）。CLI 加 `oryxos user add/list/delete/passwd/disable/enable` 子命令组管账号。REST API `/api/v1/**` 不做认证（`/api/v1/auth/*` 子树是认证专用端点：login/logout/me）。不加新 Maven 模块，不引 Spring Security 全套。

## Technical Context

**Language/Version**: Java 21（虚拟线程），Spring Boot 3.3.5，Maven 多模块（9 模块）

**Primary Dependencies**:
- `info.picocli:picocli` 4.7.6（CLI）
- `org.springframework.boot:spring-boot-starter-web`（oryxos-web）
- `org.springframework.boot:spring-boot-starter-data-jpa`（oryxos-storage）
- `org.xerial:sqlite-jdbc` 3.46.1.0
- `org.hibernate.orm:hibernate-community-dialects`（`SQLiteDialect`）
- **新增** `org.springframework.security:spring-security-crypto`（单 jar，非 `spring-boot-starter-security`；版本由 Spring Boot parent BOM 管）

**Storage**: SQLite，数据源 `jdbc:sqlite:oryxos.db`（工作目录根，**非** spec 所写 `.oryxos/oryxos.db`——以 `oryxos-boot/src/main/resources/application.yml:18-19` 实际为准，plan 对齐实际）。`spring.jpa.hibernate.ddl-auto: none` + `spring.sql.init.mode: always`（启动跑 `schema.sql`，幂等 `CREATE TABLE IF NOT EXISTS`）。

**Testing**: JUnit 5 + Spring Boot Test（`@DataJpaTest` 切片 + `@WebMvcTest`/`MockMvc`）。镜像现有 `LlmCallRepositoryTest`、`SessionApiControllerTest`。

**Target Platform**: Linux 主流发行版（也跑 Win/macOS 开发机），单可执行 fat JAR，装企业自有 K8s/VM/物理机。

**Project Type**: web-service + cli（Java 单体，Spring Boot + Picocli）

**Performance Goals**: 单节点 ≥10 Agent、≥100 并发 Session（沿用既有目标，auth filter 每请求查 DB 不缓存，BCrypt 校验 ~50ms 可接受，不破并发目标）

**Constraints**:
- 宪法原则 VI：凭证不落地，明文密码 NEVER 入代码/配置/日志/git
- 宪法原则 VIII：表结构手工 `schema.sql` 唯一权威，不依赖 Hibernate auto 迁移
- 默认关（`auth.enabled=false`），回归零破坏（SC-001）
- `/api/v1/**` 不拦，`/api/v1/health` 免认证（SC-003）
- 不引 Spring Security 全套（无 filter chain / autoconfig / RBAC）
- 不加新 Maven 模块（塞 storage + web + cli）

**Scale/Scope**: 1 新表 + 1 新实体 + 1 新 repo + 1 新 service + 1 新 filter + 1 新配置类 + 1 新 CLI 命令组（5 leaf）+ schema.sql 追加 + 2 处 pom 依赖 + application.yml 配置 + 测试 + 文档。约 15-20 个文件改动。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

逐条核对 `.specify/memory/constitution.md`（v1.1.0）八原则 + 技术栈约束 + 开发流程：

| 原则 | 结论 | 说明 |
|---|---|---|
| I. 自实现 ReAct 循环 | ✅ 不涉及 | auth 不碰 ReActLoop |
| II. Spring AI 仅协议转换+Schema | ✅ 不涉及 | auth 不碰 Spring AI |
| III. Provider 显式映射 | ✅ 不涉及 | auth 不碰 Provider |
| IV. 一个目录=一个 Agent | ✅ 不涉及 | auth 不碰 Agent 目录/ContextLoader |
| V. 审计 Day One 落库 | ✅ 不涉及 | auth 不碰 tool_invocations/llm_calls；`web_users` 是账号表非审计表，不触发该原则 |
| VI. 安全是地基，不用 SecurityManager | ✅ 通过 | 用 `spring-security-crypto` 做密码哈希（非 `SecurityManager`，JDK 21 已弃用 SecurityManager）；凭证走环境变量/哈希，明文不落配置/日志/git；`BasicAuthFilter` 是应用层 filter 非安全器 |
| VII. 同步执行+虚拟线程，不引入异步 | ✅ 通过 | `BasicAuthFilter` 同步阻塞，不引 Reactor/WebFlux/CompletableFuture |
| VIII. 配置即 Agent，实例无状态、状态外置 | ✅ 通过 | `web_users` 走 SQLite 外置状态；表结构走手工 `schema.sql`（FR-010），不依赖 `ddl-auto=update` |

**技术栈约束**:
- Java 21 + Spring Boot 3.x + Maven 多模块 ✅
- 不加新模块：WebUser/repo/service 进 `oryxos-storage`，filter/配置进 `oryxos-web`，CLI 进 `oryxos-cli` ✅（无需声明新模块理由）
- 持久化 SQLite + Spring Data JPA ✅
- 日志 Logback + SLF4J，禁 `System.out`（CLI 输出除外，命令本就用 stdout）✅
- 敏感配置 `${ENV_VAR}` 占位，启动校验走 `@Component implements ApplicationRunner`（oryxos-web，`AuthStartupCheck`），context 就绪后判 enabled 账号数（FR-006）✅

**开发流程约束**:
- Spec-Driven Development：constitution → specify → plan → tasks → analyze → implement ✅（当前在 plan 阶段）
- 质量门禁全绿：Spotless + P3C + Checkstyle + SpotBugs/FSB + OWASP Dep-Check，接 `mvn verify` ✅（实现时须过门禁）

**无违宪项**。无需 Complexity Tracking。

## Project Structure

### Documentation (this feature)

```text
specs/012-web-auth/
├── plan.md              # 本文件
├── research.md          # Phase 0 产出
├── data-model.md        # Phase 1 产出
├── quickstart.md        # Phase 1 产出
├── contracts/           # Phase 1 产出
│   └── cli-auth.md
└── checklists/
    └── requirements.md  # specify 阶段已产出
```

### Source Code (repository root)

```text
oryxos-storage/
├── src/main/java/io/oryxos/storage/
│   ├── WebUser.java                          # 新：@Entity web_users
│   ├── WebUserRepository.java               # 新：JpaRepository<WebUser,Long>
│   └── WebUserService.java                   # 新：账号 CRUD + 密码校验（plain class，@Bean 装配）
├── src/main/resources/schema.sql             # 改：追加 web_users 建表块
└── pom.xml                                   # 改：加 spring-security-crypto
└── src/test/java/io/oryxos/storage/
    └── WebUserServiceTest.java               # 新

oryxos-web/
├── src/main/java/io/oryxos/web/
│   ├── config/
│   │   ├── WebAuthProperties.java            # 改：加 sessionTtl 字段（Duration，默认 12h）
│   │   └── AuthFilterConfig.java             # 改：FilterRegistrationBean<AuthFilter>（原 BasicAuthFilter 扩为认 Basic+session）
│   ├── security/
│   │   ├── BasicAuthFilter.java               # 改：保留类名（保 T011 测试引用 + SpotBugs 注解），扩两条路径——先查 session cookie 放行，无则查 Basic 头，都无按 Accept 头分流（浏览器 302 / curl 401）。/admin/login 路径内部放行
│   │   ├── PasswordEncoderFactory.java       # 不动：DelegatingPasswordEncoder bean（{bcrypt}）
│   │   └── AuthStartupCheck.java              # 不动：@ConditionalOnWebApplication(SERVLET)
│   └── controller/
│       └── AuthApiController.java            # 新：POST /api/v1/auth/login + /logout + GET /me
├── src/main/resources/application.yml (in oryxos-boot，见下)
├── src/main/frontend/src/                     # Vue SPA
│   ├── views/LoginView.vue                    # 新：/admin/login 页（居中卡片+logo+表单，复用 tokens.css）
│   ├── router.js (或等价)                      # 改：加 /admin/login 路由 + 全局前置守卫（未登录跳 login）
│   └── styles/tokens.css                      # 复用：深色+橙+Space Grotesk
└── pom.xml                                   # 不动（spring-security-crypto 已加）
└── src/test/java/io/oryxos/web/
    ├── security/BasicAuthFilterTest.java      # 改：扩 session 路径测试（cookie 有效放行、过期跳登录、curl 401）
    ├── controller/AuthApiControllerTest.java  # 新：login/logout/me 端点
    └── config/WebAuthPropertiesTest.java      # 改：加 sessionTtl 绑定测试

oryxos-storage/
├── src/main/java/io/oryxos/storage/
│   ├── WebSession.java                        # 新：@Entity web_sessions（session_id/username/expires_at/created_at）
│   ├── WebSessionRepository.java             # 新：JpaRepository<WebSession,Long>，findBySessionId
│   └── WebUserService.java                   # 改：加 createSession/findBySessionId/deleteSession/isExpired（或新建 WebSessionService 分离）
├── src/main/resources/schema.sql             # 改：追加 web_sessions 建表块
└── src/test/java/io/oryxos/storage/
    └── WebSessionServiceTest.java             # 新：session CRUD + 过期判定

oryxos-cli/
├── src/main/java/io/oryxos/cli/
│   ├── command/UserCommand.java              # 不动（add/list/delete/passwd/disable/enable 已齐）
│   └── OryxOsCli.java                        # 不动（UserCommand 已注册）
│   └── OryxOsRuntime.java                    # 改：@Bean 装配 WebSessionRepository + WebSessionService（若新建）
└── pom.xml                                   # 不动
└── src/test/java/io/oryxos/cli/command/
    └── UserCommandTest.java                  # 新

oryxos-boot/
└── src/main/resources/application.yml        # 改：加 oryxos.web.auth.session-ttl: 12h

config/
└── application.yml.example                   # 改：加 oryxos.web.auth.session-ttl 示例

website/docs/ (或 website/zh/docs/)
└── auth.md                                   # 改：补登录页/session/登出说明（中英）
```

**Structure Decision**: 不加新 Maven 模块。WebUser/repo/service + WebSession/repo 进 `oryxos-storage`；filter/配置/AuthApiController/LoginView 进 `oryxos-web`；CLI 进 `oryxos-cli`。`OryxOsRuntime` 已扫 `io.oryxos.storage`，实体/repo 自动发现。session 服务可并入 `WebUserService`（加 session 方法）或新建 `WebSessionService`——**推荐新建 `WebSessionService`**（职责分离：账号 vs 会话，各自单一职责，测试独立）。

## Complexity Tracking

> 无违宪项，本表留空。
