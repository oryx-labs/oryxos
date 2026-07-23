---

description: "Task list for feature 012-web-auth implementation"
---

# Tasks: Web Service 认证机制（最小 Auth）

**Input**: Design documents from `/specs/012-web-auth/`

**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, contracts/cli-auth.md ✅, quickstart.md ✅

**Tests**: 含测试任务（项目质量门禁要求 `mvn verify` 全绿，auth 是安全特性必须有测试）。

**Organization**: 按 user story 分组（spec.md 两个 P1 user story）。US1=管理台启用 auth，US2=CLI 增删账号。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件，无依赖）
- **[Story]**: 属哪个 user story（US1/US2）
- 含精确文件路径

## Path Conventions

- Maven 多模块，路径形如 `oryxos-<module>/src/main/java/io/oryxos/<layer>/...`
- 镜像现有约定（见 plan.md Project Structure）

## Phase 1: Setup（共享基础设施）

**Purpose**: 依赖、配置骨架、新表

- [ ] T001 [P] 在 `oryxos-storage/pom.xml` 与 `oryxos-web/pom.xml` 两处加 `org.springframework.security:spring-security-crypto` 依赖（单 jar，非 starter-security；版本由 Spring Boot parent BOM 管。storage 用 encoder 哈希，web 声明 `PasswordEncoder` bean）
- [ ] T002 [P] 在 `oryxos-storage/src/main/resources/schema.sql` 追加 `web_users` 建表块 + `idx_web_users_username` 索引（见 data-model.md DDL，镜像现有 `llm_calls` 块风格，`CREATE TABLE IF NOT EXISTS`）
- [ ] T003 [P] 在 `oryxos-boot/src/main/resources/application.yml` 的 `oryxos:` 子树下加 `web.auth.enabled: false` + `web.auth.realm: OryxOS`（默认关，SC-001）
- [ ] T004 [P] 在 `config/application.yml.example` 加 `oryxos.web.auth.*` 示例段 + 注释说明（开 auth 前先 `oryxos user add`）

**Checkpoint**: 依赖就位，表结构定义好，配置骨架在。

---

## Phase 2: Foundational（阻塞性前置）

**Purpose**: 实体/Repo/Service/Encoder 配置——所有 user story 依赖

**⚠️ CRITICAL**: US1（filter 校验账号）和 US2（CLI 管账号）都依赖本 phase

- [ ] T005 [P] 在 `oryxos-storage/src/main/java/io/oryxos/storage/WebUser.java` 建 `@Entity @Table(name="web_users")`，字段 `id`(Long,IDENTITY)/`username`(unique)/`passwordHash`/`enabled`/`createdAt`/`updatedAt`(Instant)，`@PrePersist onCreate()`，无 Lombok，单行 Javadoc 注"表结构以手工 schema.sql 为唯一权威"（镜像 `LlmCall.java`）
- [ ] T006 [P] 在 `oryxos-storage/src/main/java/io/oryxos/storage/WebUserRepository.java` 建 `interface WebUserRepository extends JpaRepository<WebUser, Long>`，加 `Optional<WebUser> findByUsername(String)` + `boolean existsByUsername(String)`，无 `@Repository` 注解
- [ ] T007 在 `oryxos-storage/src/main/java/io/oryxos/storage/WebUserService.java` 建 plain class（非 `@Service`），构造注入 `WebUserRepository` + `PasswordEncoder`。方法：`create(username,rawPw)`、`delete(username)`、`changePassword(username,rawPw)`、`disable(username)`、`list()`、`verify(username,rawPw)→boolean`、`hasEnabledAccount()→boolean`。每次 mutate 手动 `setUpdatedAt(Instant.now())`（镜像 `JpaScheduledTaskStore`）。校验 username 非空/无空格/≤64，password ≥8
- [ ] T008 [P] 在 `oryxos-web/src/main/java/io/oryxos/web/config/WebAuthProperties.java` 建 `@ConfigurationProperties(prefix="oryxos.web.auth")`，字段 `boolean enabled`(默认 false) + `String realm`(默认 "OryxOS")
- [ ] T009 [P] 在 `oryxos-web/src/main/java/io/oryxos/web/security/PasswordEncoderFactory.java` 建 `@Configuration` class，`@Bean PasswordEncoder` 返 `PasswordEncoderEncoderFactories.createDelegatingPasswordEncoder()`（`{bcrypt}` 前缀，FR-009）
- [ ] T010 改 `oryxos-cli/src/main/java/io/oryxos/cli/OryxOsRuntime.java`：(a) `@EnableConfigurationProperties({...})` 列表加 `WebAuthProperties.class`；(b) 加 `@Bean WebUserService webUserService(WebUserRepository, PasswordEncoder)` 装配（镜像 `sessionManager` 在 line 290）

**Checkpoint**: Foundation ready——实体/repo/service/encoder/配置全就位，可装 Bean，filter 和 CLI 都能用。

---

## Phase 3: User Story 1 - 启用认证保护管理台 (Priority: P1) 🎯 MVP

**Goal**: `auth.enabled=true` 时 `/admin/**` 弹 Basic Auth，`/api/v1/**` 不受影响，`/api/v1/health` 免认证。默认关回归零破坏。

**Independent Test**: quickstart.md 场景二——无凭据 `/admin/` 返 401 + `WWW-Authenticate`，正确凭据 200，REST 全不受影响。场景一——默认关全 200。

### Tests for User Story 1

> 先写测试，先失败再实现（TDD，auth 安全特性）

- [ ] T011 [P] [US1] 在 `oryxos-web/src/test/java/io/oryxos/web/security/BasicAuthFilterTest.java` 写 `@WebMvcTest` + `MockMvc`：无凭据 `/admin/` 期望 401 + `WWW-Authenticate: Basic realm="OryxOS"`；正确凭据期望 200；错误凭据 401；禁用账号 401；`/api/v1/health` 无凭据 200（免认证）；`/api/v1/profiles` 无凭据 200（REST 不拦）；`auth.enabled=false` 时 `/admin/` 无凭据 200（默认关）
- [ ] T012 [P] [US1] 在 `oryxos-web/src/test/java/io/oryxos/web/config/WebAuthPropertiesTest.java` 写配置绑定测试：`enabled` 默认 false，`realm` 默认 "OryxOS"，yml 覆盖生效

### Implementation for User Story 1

- [ ] T013 [P] [US1] 在 `oryxos-web/src/main/java/io/oryxos/web/security/BasicAuthFilter.java` 建 `extends OncePerRequestFilter`：注入 `WebUserService` + `WebAuthProperties` + `ObjectMapper`。`auth.enabled=false` 直接放行；`true` 时取 `Authorization: Basic` 头 → Base64 解码拆 user/pass → `WebUserService.verify` → 失败写 401 JSON `ApiResponse.error(401,"Unauthorized")` + `WWW-Authenticate: Basic realm="<配置值>"`；成功 `chain.doFilter`。不抛异常（advice 捕不到 filter 异常）
- [ ] T014 [US1] 在 `oryxos-web/src/main/java/io/oryxos/web/config/AuthFilterConfig.java` 建 `@Configuration`，`@Bean FilterRegistrationBean<BasicAuthFilter>`：`addUrlPatterns("/admin/**")` + `setOrder(Ordered.HIGHEST_PRECEDENCE + 10)`（仅 `/admin/**`，`/api/v1/**` 不拦，依赖 T013）
- [ ] T015 [US1] 在 `oryxos-web/src/main/java/io/oryxos/web/security/AuthStartupCheck.java` 建 `@Component implements ApplicationRunner`：注入 `WebAuthProperties` + `WebUserService`，`run()` 内判 `auth.enabled==true && !hasEnabledAccount()` 时抛 `IllegalStateException` 阻断启动，message 提示 `oryxos user add`（FR-006，依赖 T010；放 oryxos-web 与 filter 同模块）

**Checkpoint**: US1 全功能——开 auth 后管理台要登录，REST 不受影响，无账号启动阻断。默认关回归零破坏。

---

## Phase 4: User Story 2 - CLI 增删账号 (Priority: P1)

**Goal**: `oryxos user add/list/delete/passwd/disable/enable` 子命令组，密码不回显，加账号免重启即生效。

**Independent Test**: quickstart.md 场景四 + `user list` 不显密码——`oryxos user add alice` 交互输密码 → `user list` 见 alice 不显 hash → Basic Auth 用 alice 登录成功 → `user delete alice` → 登录 401。

### Tests for User Story 2

- [ ] T016 [P] [US2] 在 `oryxos-storage/src/test/java/io/oryxos/storage/WebUserServiceTest.java` 写 `@DataJpaTest` + `@AutoConfigureTestDatabase(replace=NONE)` + `@DynamicPropertySource`（设 `ddl-auto=none` + SQLiteDialect + `sql.init.mode=always`，镜像 `LlmCallRepositoryTest`）：`create` 存 hash 非明文 + 每次 salt 不同；重名抛错；`verify` 对的真假错；`disable` 后 `verify` 返 false；`hasEnabledAccount` 空=false 有=true；password <8 抛错；username 空格/超长抛错
- [ ] T017 [P] [US2] 在 `oryxos-cli/src/test/java/io/oryxos/cli/command/UserCommandTest.java` 写 CLI 集成测试：`add`/`list`/`delete`/`passwd`/`disable`/`enable` 各覆盖成功 + 失败路径；`list` 输出 0% 含密码/hash；mock `System.console` 或用 ` ByteArrayInputStream` 测密码读取（验证不回显由 console API 保证，测逻辑即可）

### Implementation for User Story 2

- [ ] T018 [P] [US2] 在 `oryxos-cli/src/main/java/io/oryxos/cli/command/UserCommand.java` 建 parent `@Command(name="user", subcommands={AddCommand.class, ListCommand.class, DeleteCommand.class, PasswdCommand.class, DisableCommand.class, EnableCommand.class})` implements `Runnable`（bare `oryxos user` 打 usage），镜像 `ProfileCommand`
- [ ] T019 [US2] 在 `UserCommand.java` 加 static nested `AddCommand` `@Command(name="add")`：`@Parameters(index="0") String username`。`run()` 内 `new SpringApplicationBuilder(OryxOsRuntime.class).web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF).run()` try-with-resources，`context.getBean(WebUserService.class)`。`System.console().readPassword` 读密码（null 报错拒绝）+ 确认，校验后 `create`，stdout `Created user '<name>'`。重名/短密码/不一致/console null → stderr + 退出码 1（依赖 T018，镜像 `ChatCommand` heavy 模式）
- [ ] T020 [US2] 在 `UserCommand.java` 加 static nested `ListCommand` `@Command(name="list")`：heavy 起 Spring，`context.getBean(WebUserService.class).list()`，输出表格 `USERNAME/ENABLED/CREATED_AT`，**不含密码/hash**。空表提示 `No users found...`，退出码 0（依赖 T018）
- [ ] T021 [US2] 在 `UserCommand.java` 加 static nested `DeleteCommand` `@Command(name="delete")`：`@Parameters(index="0") String username`，heavy 起 Spring，`delete`，成功 stdout / 不存在 stderr + 退出码 1（依赖 T018）
- [ ] T022 [US2] 在 `UserCommand.java` 加 static nested `PasswdCommand` `@Command(name="passwd")`：`@Parameters(index="0") String username`，heavy 起 Spring，`readPassword` 读新密码 + 确认，`changePassword`，stdout `Password updated`。用户不存在/短/不一致/console null → stderr + 退出码 1（依赖 T018）
- [ ] T023 [US2] 在 `UserCommand.java` 加 static nested `DisableCommand` `@Command(name="disable")`：`@Parameters(index="0") String username`，heavy 起 Spring，`disable`，stdout `Disabled user` / 不存在 stderr + 退出码 1（依赖 T018）
- [ ] T023a [US2] 在 `UserCommand.java` 加 static nested `EnableCommand` `@Command(name="enable")`：`@Parameters(index="0") String username`，heavy 起 Spring，`enable`，stdout `Enabled user` / 不存在 stderr + 退出码 1（对称 disable，禁用后可恢复；依赖 T018）
- [ ] T024 [US2] 改 `oryxos-cli/src/main/java/io/oryxos/cli/OryxOsCli.java`：`subcommands={...}` 数组加 `UserCommand.class`（line 26-37）

**Checkpoint**: US2 全功能——五子命令跑通，账号管理可用，`user list` 不泄密码。

---

## Phase 4b: User Story 3 - 浏览器登录管理台 (Priority: P1)

**Goal**: 浏览器走 session/cookie + 登录页，curl 走 Basic Auth，两者并存。

**Independent Test**: quickstart 场景五——浏览器访问 `/admin/` → 跳 `/admin/login` → 输对 → 跳 `/admin/` SPA 加载 → 点登出 → 跳回登录页；curl `-u admin:pw` `/admin/` → 200。

### Tests for User Story 3

- [ ] T031 [P] [US3] 在 `oryxos-storage/src/test/java/io/oryxos/storage/WebSessionServiceTest.java` 写 `@DataJpaTest` + `@DynamicPropertySource`（镜像 WebUserServiceTest）：`createSession` 存 session_id(UUID)+expires_at+username；`findBySessionId` 命中/未命中；`isExpired` 过期判定；`deleteSession` 删行；过期行 filter 顺手删（惰性清）
- [ ] T032 [P] [US3] 在 `oryxos-web/src/test/java/io/oryxos/web/controller/AuthApiControllerTest.java` 写 `@WebMvcTest` + MockMvc：`POST /login` 对返 200+Set-Cookie（HttpOnly+SameSite=Strict+Path=/）/错返 401（"Invalid username or password"，不区分原因）；`POST /logout` 清 cookie + 删 session；`GET /me` 已登录返用户名/未登录 401

### Implementation for User Story 3

- [ ] T033 [P] [US3] 在 `oryxos-storage/src/main/java/io/oryxos/storage/WebSession.java` 建 `@Entity @Table(name="web_sessions")`，字段 `id`(Long,IDENTITY)/`sessionId`(String,unique)/`username`/`expiresAt`(Instant)/`createdAt`(Instant)，`@PrePersist`，无 Lombok（镜像 WebUser）
- [ ] T034 [P] [US3] 在 `oryxos-storage/src/main/java/io/oryxos/storage/WebSessionRepository.java` 建 `interface WebSessionRepository extends JpaRepository<WebSession,Long>`，加 `Optional<WebSession> findBySessionId(String)` + `void deleteBySessionId(String)`
- [ ] T035 [US3] 在 `oryxos-storage/src/main/java/io/oryxos/storage/WebSessionService.java` 建 plain class（构造注入 `WebSessionRepository` + `WebAuthProperties`），方法 `create(username)→WebSession`（UUID.randomUUID + `expires_at=now+sessionTtl`）、`findValid(sessionId)→Optional<WebSession>`（查到且未过期返，过期顺手 `delete` 惰性清）、`delete(sessionId)`、`isExpired(WebSession)`
- [ ] T036 [P] [US3] 在 `oryxos-storage/src/main/resources/schema.sql` 追加 `web_sessions` 建表块 + `idx_web_sessions_session` 索引（见 data-model.md DDL）
- [ ] T037 [US3] 在 `oryxos-web/src/main/java/io/oryxos/web/config/WebAuthProperties.java` 加 `Duration sessionTtl` 字段（默认 12h，`@DurationUnit(ChronoUnit.HOURS)` 或 yml `12h`）
- [ ] T038 [US3] 在 `oryxos-web/src/main/java/io/oryxos/web/controller/AuthApiController.java` 建 `@RestController @RequestMapping("/api/v1/auth")`：`POST /login`（接 `{username,password}` JSON → `WebUserService.verify` → 对则 `WebSessionService.create` + `Set-Cookie: oryxos_session=<id>; Path=/; HttpOnly; SameSite=Strict`(+Secure 若 HTTPS) → 返 `ApiResponse.ok({username})`；错则 401 `ApiResponse.error(401,"Invalid username or password")`）；`POST /logout`（从 cookie 取 session id → `delete` + 清 cookie → 200）；`GET /me`（从 cookie 取 session → `findValid` → 200 返 username / 401）
- [ ] T039 [US3] 改 `oryxos-web/src/main/java/io/oryxos/web/security/BasicAuthFilter.java`（**保留类名**，不改名——保 T011 测试引用 + SpotBugs 注解），扩两条路径：(1) 查 `oryxos_session` cookie → `WebSessionService.findValid`（未过期即放行，不查 user enabled——Clarifications Q1 B）→ 放行；(2) 无 session 则查 `Authorization: Basic` 头 → `WebUserService.verify`（原逻辑）→ 放行；(3) 都无——`Accept` 头含 `text/html` → 302 跳 `/admin/login`；否则 401 + `WWW-Authenticate`。`/admin/login` 路径（`requestURI.startsWith("/admin/login")`）内部放行（含其静态资源）
- [ ] T040 [US3] 改 `oryxos-web/src/main/java/io/oryxos/web/config/AuthFilterConfig.java`：filter 注册 URL 模式仍 `/admin/*`，`/admin/login` 放行在 filter 内部判路径（T039 实现），此 config 不改 URL 模式
- [ ] T041 [US3] 改 `oryxos-cli/src/main/java/io/oryxos/cli/OryxOsRuntime.java`：`@Bean` 装配 `WebSessionRepository`（JPA 自动扫）+ `WebSessionService`（构造注入 repo + `WebAuthProperties`）
- [ ] T042 [P] [US3] 在 `oryxos-web/src/main/frontend/src/views/LoginView.vue` 建登录页：居中卡片（logo + username 输入 + password 输入 + 登录按钮），复用 `src/styles/tokens.css`（深色 + 橙 + Space Grotesk）。提交调 `POST /api/v1/auth/login` → 200 跳 `/admin/`；401 显示"用户名或密码错误"（密码框清空、用户名保留）
- [ ] T043 [US3] 改 `oryxos-web/src/main/frontend/src/router.js`（或 App.vue 路由）：加 `/admin/login` 路由 → LoginView；全局前置守卫（`beforeEach`）：未登录访问 `/admin/**`（除 login）→ 跳 `/admin/login`；已登录访问 `/admin/login` → 跳 `/admin/`
- [ ] T044 [US3] 改 `oryxos-web/src/main/frontend/src/App.vue`（或 Layout）：加"登出"按钮（调 `POST /api/v1/auth/logout` → 跳 `/admin/login`）

**Checkpoint**: US3 全功能——浏览器登录页登录/登出，curl Basic 并存，session 过期跳登录。

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: 文档、门禁、端到端验证

- [ ] T025 [P] 在 `website/docs/auth.md` 补登录页/session/登出说明（开 auth 步骤、浏览器登录页、curl Basic、REST 不受影响、HTTPS 前置提醒）
- [ ] T026 [P] 在 `website/zh/docs/auth.md` 补中文（同上）
- [ ] T027 [P] 在 `website/docs/cli.md` 与 `website/zh/docs/cli.md` 补 `oryxos user` 命令组说明（已含 add/list/delete/passwd/disable/enable，确认）
- [ ] T028 跑 `mvn -q verify` 过全门禁（Spotless + P3C + Checkstyle + SpotBugs/FSB + OWASP Dep-Check）
- [ ] T029 跑 quickstart.md 全场景验证（场景一~五），含免重启验证：serve 运行中 `oryxos user add bob`，不重启用 bob curl `/admin/` 期望 200（FR-007）
- [ ] T030 安全复查：`web_users.password_hash` + `web_sessions` DB 中 0% 明文密码；`oryxos user list` 0% 含密码；日志 0% 明文密码；明文未入 git/配置
- [ ] T045 [P] 在 `specs/012-web-auth/quickstart.md` 加场景五（浏览器登录页 + 登出 + session 过期 + curl Basic 并存）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，立即开始。T001 先（后续编译需依赖）
- **Foundational (Phase 2)**: 依赖 Phase 1。**阻塞**所有 user story
- **US1 (Phase 3)**: 依赖 Phase 2（需 `WebUserService`/`WebAuthProperties`/`PasswordEncoder`）。US1 的 filter 实现会被 US3（Phase 4b）扩——US1 先做 Basic 版，US3 再扩 session 路径
- **US2 (Phase 4)**: 依赖 Phase 2（需 `WebUserService`）。与 US1 可并行
- **US3 (Phase 4b)**: 依赖 Phase 2 + US1（扩 `BasicAuthFilter` 为 `AuthFilter`，加 session 路径 + AuthApiController + LoginView）。在 US1 之后
- **Polish (Phase 5)**: 依赖 US1+US2+US3 完成

### User Story Dependencies

- **US1 (P1)**: Phase 2 完成后开始。独立可测（filter Basic 版 + 配置）
- **US2 (P1)**: Phase 2 完成后开始。独立可测（CLI + service）
- **US3 (P1)**: Phase 2 + US1 完成后开始。扩 filter + 加 session/controller/LoginView
- **US1 ⫻ US2**: 互不依赖，可并行
- **US3 ⫻ US2**: 互不依赖（US3 改 web，US2 改 cli，不冲突），但 US3 依赖 US1 的 filter 已存在

### Within Each User Story

- 测试先写先失败（TDD）
- 实体 → service → filter/command
- service 前于 endpoint/command
- 每 task 后 commit

### Parallel Opportunities

- Phase 1: T002/T003/T004 并行（不同文件）
- Phase 2: T005/T006/T008/T009 并行（实体/repo/配置/encoder 互不依赖）；T007 依赖 T005+T006；T010 依赖 T007+T008+T009
- Phase 3 (US1): T011/T012 测试并行；T013 依赖 T011；T014 依赖 T013；T015 依赖 T010
- Phase 4 (US2): T016/T017 测试并行；T018 先（parent）；T019-T023a 串行（同文件 `UserCommand.java` static class，物理不可并行，按序加）；T024 独立改 `OryxOsCli`
- Phase 4b (US3): T031/T032 测试并行；T033/T034/T036/T037 并行（实体/repo/schema/配置）；T035 依赖 T033+T034+T037；T038 依赖 T035；T039 依赖 T035+T013（扩 filter）；T040/T041/T042/T043/T044 后续
- US2 与 US3 跨 story 可并行（不同模块：cli vs web），但 US3 依赖 US1 filter 已存在

---

## Parallel Example: Phase 2 Foundational

```bash
# 四个独立件并行（不同文件）：
Task: "WebUser 实体 in oryxos-storage/.../WebUser.java"
Task: "WebUserRepository in oryxos-storage/.../WebUserRepository.java"
Task: "WebAuthProperties in oryxos-web/.../config/WebAuthProperties.java"
Task: "PasswordEncoder bean in oryxos-web/.../security/PasswordEncoderFactory.java"
# 然后（依赖上面）：
Task: "WebUserService in oryxos-storage/.../WebUserService.java"  # 依赖 WebUser + WebUserRepository + PasswordEncoder
Task: "OryxOsRuntime 装配 in oryxos-cli/.../OryxOsRuntime.java"   # 依赖 WebUserService + WebAuthProperties
```

## Parallel Example: US1 vs US2

```bash
# Phase 2 完成后，两个 P1 story 并行（不同模块）：
Developer A (oryxos-web):  BasicAuthFilter + AuthFilterConfig + 启动校验 + filter 测试
Developer B (oryxos-cli):  UserCommand 五 leaf + OryxOsCli 注册 + CLI 测试
```

---

## Implementation Strategy

### MVP First (US1 Only)

1. Phase 1: Setup
2. Phase 2: Foundational（CRITICAL，阻塞）
3. Phase 3: US1
4. **STOP VALIDATE**: quickstart 场景一 + 场景二（用配置文件或测试预置账号验证 filter）
5. Deploy/demo

### Incremental Delivery

1. Setup + Foundational → 基础就位
2. + US1 → 独立测（filter 生效）→ MVP
3. + US2 → 独立测（CLI 管账号）→ 完整功能
4. + Polish → 文档/门禁/端到端
5. 每 story 加值不破前序

### Parallel Team Strategy

- Phase 2 完后：Dev A=US1(web)，Dev B=US2(cli) 并行
- US1/US2 独立模块无冲突
- 端到端验证需两者都完

---

## Notes

- [P] = 不同文件无依赖可并行
- `UserCommand.java` 内 6 个 leaf 是同文件 static class——T019-T023a 已去 `[P]` 标记，按序串行实现（物理同文件不可并行）
- 宪法合规：原则 VI（凭证不落地）+ VIII（手工 schema.sql）已内建；原则 VII（同步）filter 同步阻塞
- 质量门禁：实现时每 task 跑 `mvn -q verify` 子集，T028 全量
- commit 每 task 或逻辑组
- 敏感：明文密码不入 git/配置/日志（T030 复查）
