# Tasks: Web Service 与第一版管理平台

**Input**: specs/009-web-service/（plan.md / research.md / data-model.md / contracts/rest-api.md / quickstart.md）

**Tests**: 课件"验收 harness"——`SessionApiControllerTest`（@WebMvcTest 切片）、`GlobalExceptionHandlerTest`、`WebSmokeIT`(@Tag integration)。测试方法名英文 + `@DisplayName` 保留课件中文。

**Organization**: Setup → Foundational（信封复用/接口扩展/异常/DTO/WebConfig）→ US1 会话 → US2 invoke → US3 只读+管理台 → 反向文档同步 + Polish。

**既定决策（H0 + plan 确认）**：统一信封=既有 `ApiResponse`（不建 ErrorBody）；`GlobalExceptionHandler` 扩展既有类；发消息/invoke 走 `AgentService.process` 同一入口；**两处前序接口扩展**（tasks 停点确认）`SessionManager.archive`、`MemoryService.readAll`；`application.yml` 排除 `OpenAiAutoConfiguration`；管理台 Vue 用 `oryxos-admin-ui` skill 生成 + frontend-maven-plugin。

## Phase 1: Setup

- [ ] T001 基线 & 依赖核实：`mvn test -q` 确认 16~25 全绿；`mvn dependency:tree -pl oryxos-web | grep -E "starter-web|springdoc|starter-test|spotbugs-annotations"` 确认到位；`node -v && npm -v` 确认前端可构建
- [ ] T002 `oryxos-boot/src/main/resources/application.yml`：`spring.autoconfigure.exclude` 追加 `org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration`（保留既有 DashScope）——serve 只需 `DEEPSEEK_API_KEY`（确切全限定名以 dependency:tree 锁定的 spring-ai 版本为准）
- [ ] T003 `oryxos-web/pom.xml`：+ `frontend-maven-plugin`（`install-node-and-npm` + `npm ci` + `npm run build` 绑进 `generate-resources`/`prepare-package`，产物落 `src/main/resources/static/admin/`）

## Phase 2: Foundational（阻塞所有 US——接口扩展/异常/DTO/WebConfig）

- [ ] T004 [P] **改造点1** `oryxos-core/.../session/SessionManager.java` + `oryxos-storage/.../JpaSessionManager.java`：接口加 `void archive(String sessionId)`；实现 `findById`→`setStatus("archived")` + `setArchivedAt(now)`→save，不存在抛 `SessionNotFoundException`；`SessionManagerTest` 补 archive 用例（归档后 status=archived、不存在抛错）
- [ ] T005 [P] **改造点2** `oryxos-core/.../memory/MemoryService.java` + `oryxos-memory/.../MemoryServiceImpl.java`：接口加 `String readAll()`；实现 `return store.load();`（无缓存）；`MemoryServiceImplTest` 补 readAll 用例（返回 store 全文）
- [ ] T006 [P] 异常类型：`oryxos-web/.../` 新增轻量 `SessionNotFoundException`/`ProviderUnavailableException`/`AgentTimeoutException`（若前序无）；消息超限/非法参数复用既有 `IllegalArgumentException` 或新 `InvalidRequestException`——本节对外概念，交付物点名
- [ ] T007 [P] DTO（record，`oryxos-web/.../controller/dto/`）：`CreateSessionRequest`/`MessageRequest`/`MessageResponse`/`SessionView`/`ProfileView`/`ToolView`/`InfoView`——投影既有对象、不回传内部实体
- [ ] T008 `oryxos-web/.../GlobalExceptionHandler.java`（扩展既有）：补 `SessionNotFoundException→404`、`ProviderUnavailableException→503`、超时→504、消息超限/非法→400；兜底 `Exception→500` **只给统一话术、不含内部 message**；保留既有 IllegalArgumentException/NoResourceFound/IllegalStateException 映射；全部经 `ApiResponse.error`
- [ ] T009 `oryxos-web/.../config/WebConfig.java`：`/admin/**` 未命中回落 `admin/index.html`（`/api/v1/**` 不受影响）+ 静态资源映射

**Checkpoint**: 信封/异常/DTO/接口扩展就位，Controller 可写。

## Phase 3: US1 会话管理（P1，harness 先行）

**Goal**: 会话四端点（创建/发消息/查历史/归档），发消息走 AgentService.process，Controller 不夹带私货。

**Independent Test**: `mvn test -pl oryxos-web -am -Dtest=SessionApiControllerTest` 全绿。

- [ ] T010 [US1] **harness 先行** `oryxos-web/.../controller/SessionApiControllerTest.java`（@WebMvcTest，mock AgentService/SessionManager）：`messageOver32kb_returns400` `@DisplayName("消息超32KB_返回400")`、`sessionNotFound_returns404` `@DisplayName("会话不存在_返回404")`、`normalMessage_callsProcessExactlyOnce` `@DisplayName("正常发消息_编排入口恰被调一次")`（`verify(agentService).process(...)` + Controller 不夹带私货）
- [ ] T011 [US1] `oryxos-web/.../controller/SessionApiController.java`：`POST /api/v1/sessions`（getOrCreate("web",userId,profile)）、`POST /{id}/messages`（get→404；≤32KB→400；AgentService.process）、`GET /{id}`（投影 SessionView，messages 尾≤100）、`DELETE /{id}`（SessionManager.archive）；只校验/包装/兜错
- [ ] T012 [US1] **harness** `oryxos-web/.../GlobalExceptionHandlerTest.java`：每类异常→约定状态码、响应体统一 `ApiResponse`（`$.code`/`$.message`）；**关键回归** `internalDetailsNeverLeakIn500` `@DisplayName("内部异常细节_绝不能出现在500响应里")`（注入含 `jdbc:sqlite` 的异常，断言 body not contains 连接串、message=统一话术）
- [ ] T013 [US1] 阶段门禁：`mvn test -pl oryxos-web -am -Dtest='SessionApiControllerTest,GlobalExceptionHandlerTest' -Dsurefire.failIfNoSpecifiedTests=false` 绿

## Phase 4: US2 无状态调用（P1）

**Goal**: `POST /agents/{name}/invoke` 走同一 AgentService.process。

- [ ] T014 [US2] `oryxos-web/.../controller/AgentApiController.java`：`POST /api/v1/agents/{name}/invoke`（MessageRequest，≤32KB→400；建临时会话/无状态跑 AgentService.process 返回回复；Agent 不存在→404）；`AgentApiControllerTest`（@WebMvcTest）断言走 process、超限 400、不存在 404

## Phase 5: US3 只读观察 + 管理台（P2）

**Goal**: 四个只读查询端点 + Vue 管理台五页，只读、同源。

- [ ] T015 [P] [US3] `oryxos-web/.../controller/ProfileApiController.java`：`GET /api/v1/profiles`（ProfileRegistry.all→ProfileView[]）
- [ ] T016 [P] [US3] `oryxos-web/.../controller/MemoryApiController.java`：`GET /api/v1/memory`（MemoryService.readAll 全文）
- [ ] T017 [P] [US3] `oryxos-web/.../controller/ToolApiController.java`：`GET /api/v1/tools`（ToolRegistry.all→ToolView[]）
- [ ] T018 [P] [US3] `oryxos-web/.../controller/SystemApiController.java`：`GET /api/v1/health`（ok）、`GET /api/v1/info`（运行信息 + providerMap 名称/已配置状态，不 live ping）
- [ ] T019 [US3] `oryxos-web/.../WebSmokeIT.java`（@SpringBootTest + `@Tag("integration")` CI 跳）：真实上下文起，`/health`/`/info`/`/profiles`/`/tools` 可达，JPA 仓库扫描 >0（复现 18 节 Found 0 坑的防线）
- [ ] T020 [US3] 管理台前端：调 `oryxos-admin-ui` skill 生成 Vue 3 + Vite 五页（会话/Profile/Tool/记忆/状态）到 `oryxos-web/src/main/frontend/`，`vite base '/admin/'`、outDir→`../resources/static/admin/`，只读无写入口、三态齐、出错显示 `ApiResponse.message`、视觉用首页 token
- [ ] T021 [US3] 前端冒烟（人工/半自动）：`mvn package`（frontend-maven-plugin 构建 admin）→ `serve` → `open /admin` 五页渲染真实数据 + `open /swagger-ui`

## Phase 6: 反向文档同步 + Polish

- [ ] T022 [P] 反向同步 `docs/TechnicalSolution.md §7`：`GlobalExceptionHandler` 的 `errorCode/message/timestamp`（ErrorBody）→ 复用 `ApiResponse`（code/message/data/timestamp）
- [ ] T023 [P] 反向同步 `docs/class/第26节*.md §三`：`ErrorBody` 代码/文字 → `ApiResponse`
- [ ] T024 全仓硬门禁：`mvn clean verify` 全绿（含 Spotless/P3C/PMD/Checkstyle/SpotBugs/FindSecBugs + 前端构建），红了修实现
- [ ] T025 H4 六条自查 + 交付物 ls/grep 核对（发消息/invoke 走 process grep、无明文 key、session_id 只在 SessionManager 内拼、Controller 无 Reactor/自建线程池、500 不泄漏）
- [ ] T026 验收报告：`specs/009-web-service/acceptance-report.md`（六项证据 + 人工项：真模型发消息、CLI/REST 同源、503/504 注入、并发压、管理台五页、启动只需一个 key）

## Dependencies

- T001→全部；T002/T003 [P]。
- T004/T005/T006/T007 [P] 并行（不同文件）→ T008（异常映射引用 T006 异常类）；T009 [P]。
- T004→T011（DELETE 用 archive）；T005→T016（/memory 用 readAll）。
- T010（harness）先于/伴随 T011；T012 伴随 T008。T011/T014/T015-018 依赖 T007 DTO + T008 异常。
- T015-T018 [P] 并行（不同 controller 文件）。
- 主代码全绿 → T019 冒烟 / T020 前端 → T021。
- 全部 → T022/T023 [P] 文档 → T024 → T025 → T026。

## Parallel Opportunities

- T004 / T005 / T006 / T007（接口扩展 / 异常 / DTO，不同文件）。
- T015 / T016 / T017 / T018（四个只读 controller，不同文件）。
- T022 / T023（两份文档）。

## Implementation Strategy

- **MVP** = Phase 2 + Phase 3（接口扩展 + 会话四端点 + 异常统一 + 两个关键回归 harness）——REST 对外通道 + 统一信封证明就位。
- Phase 4/5 补 invoke、只读端点、Vue 管理台；Phase 6 反向文档 + 门禁。
- 管理台前端是 AI 生成 + 构建接线（`oryxos-admin-ui` skill），非 Java 单测覆盖，验收走冒烟 + 人工。
- 每阶段门禁当场修红。
