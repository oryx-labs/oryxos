# Tasks: 动态管理 Agent —— 一句话生成、上传即上线

**Feature**: `specs/011-agent-lifecycle` | **Branch**: `class-30`

**Input**: plan.md / research.md / data-model.md / contracts/internal-api.md / quickstart.md

约束：不新增第三方依赖（监听用 JDK `WatchService`）、不新增数据表 / 不碰 schema；避 Java 18+ 语法（`default ->`、record 模式、pattern-matching switch；`WatchEvent.kind()` 用等值比较）；测试方法名英文 + `@DisplayName` 中文守点；错误码复用既有 `IllegalArgumentException`(400)/`ResourceNotFoundException`(404)，不新造异常类型。

---

## Phase 1: Setup

- [ ] T001 依赖复核：`mvn dependency:tree` 确认无新增第三方依赖（`WatchService` 属 JDK）；确认 `.oryxos/archive/` 归档区约定（`AgentStore.archive` 建）。无 `pom.xml` 改动。

---

## Phase 2: Foundational（阻塞多个故事的共享改造 / 基础件）

- [ ] T002 [P] 新增 `AgentStore`（`oryxos-core/src/main/java/io/oryxos/core/agent/AgentStore.java`）：`write(name, agentMarkdown)→写 .oryxos/agents/<name>/AGENT.md 返回目录 Path`、`delete(agentDir)→递归删（回滚用）`、`archive(name)→整个目录移入 .oryxos/archive/<name>/（不物理删；同名加时间戳后缀）`。伴随 `AgentStoreTest`：写→读回、archive 后原目录消失且 archive 出现、delete 回滚。
- [ ] T003 改造 29 节 `oryxos-core/.../agent/AgentScheduler.java`：新增 `public void unregisterProfile(Profile profile)`——遍历 `profile.schedules()`，从既有私有 `scheduledTasks` 取 `ScheduledFuture` 调 `cancel(false)` 再 `scheduledTasks.remove(id)`；不动 `taskLocks`。既有 `registerProfile`/`registerAll`/`hasScheduledTask` 不变。补 `AgentSchedulerRegisterTest`：`unregisterProfile` 后 `hasScheduledTask` 变 false（用 `doReturn(future)` 桩）。
- [ ] T004 [P] 新增 `GenerateProperties`（`oryxos-cli/.../GenerateProperties.java` 或 config 包，`@ConfigurationProperties("oryxos.generate")`）：`provider`、`model`；`provider` 缺省取 `oryxos.providers` 第一个（装配层解析）。绑定 `oryxos.generate.*`。

---

## Phase 3: User Story 1 — 建一个 Agent 即上线（免重启） (P1) 🎯 MVP

**Goal**：`POST /api/v1/agents` 校验→写目录→派生注册，免重启可见；失败回滚不留半个。

**Independent Test**：mock 依赖，`create` 一个合法定义 → verify 走 register；注入 register 失败 → verify 回滚删目录、`exists` false；name 冲突 → 第一步拒、`write` never。

- [ ] T005 [P] [US1] `AgentLifecycleServiceTest`（`oryxos-core/src/test/.../agent/AgentLifecycleServiceTest.java`）create 部分：`create_persistsThenRegisters`、`create_nameConflict_rejectedBeforeAnyWrite`（`agentStore.write` never）、`create_registerFails_rollsBackWrittenDir`（`doThrow` register → verify `agentStore.delete`、`registerProfile` never）。@DisplayName 保留课件守点。
- [ ] T006 [US1] 新增 `AgentLifecycleService`（`oryxos-core/.../agent/AgentLifecycleService.java`）：构造注入 `ProviderService`/`AgentLoader`/`ProfileRegistry`/`AgentScheduler`/`AgentStore` + 生成 provider/model；实现 `register(Path agentDir)`（deriveProfile→register→schedules 非空则 registerProfile）、`create(name, md)`（exists→IAE；write→try register catch delete+throw）、`get(name)`（无→`ResourceNotFoundException`）。让 T005 转绿。
- [ ] T007 [P] [US1] `AgentApiControllerTest`（`oryxos-web/src/test/.../controller/AgentApiControllerTest.java`，26 节已有，加用例）：`create` 200 返回 AgentView、name 冲突 → 400、统一 `ApiResponse`；`get` 200 / 不存在 404。standalone MockMvc、mock lifecycle。
- [ ] T008 [US1] 改造 `oryxos-web/.../controller/AgentApiController.java`：加 `POST /agents`（create）、`GET /agents`（list）、`GET /agents/{name}`（get），薄转发 `AgentLifecycleService`；新增 DTO `AgentView`、`CreateAgentRequest`（`oryxos-web/.../controller/dto/`）。invoke 不变。
- [ ] T009 [US1] 装配层 `oryxos-cli/.../OryxOsRuntime.java`：`AgentStore`、`AgentLifecycleService`（注入生成 provider/model：`GenerateProperties` 缺省取 `providerMap` 第一个 key + 配置 model）、`AgentApiController` 新依赖的 @Bean 接线。

---

## Phase 4: User Story 2 — 丢一个目录也即上线 (P1)

**Goal**：`WorkspaceWatcher` 实时监听 `.oryxos/agents/`，变更走同一段 `register(agentDir)`；免重启。

**Independent Test**：`handleChange(dir, ENTRY_CREATE)` → verify `lifecycle.register`、Agent 进 `ProfileRegistry`；`ENTRY_DELETE` → 注销；坏目录 handleChange 不抛、不拖垮。

- [ ] T010 [P] [US2] `WorkspaceWatcherTest`（`oryxos-core/src/test/.../agent/WorkspaceWatcherTest.java`）：`handleChange_create_registersAgent`（真 `ProfileRegistry` + mock/真 lifecycle，Agent 出现）、`handleChange_delete_unregisters`、`handleChange_badDir_isSkipped_watcherSurvives`。直接调 `handleChange`，不依赖真实 WatchService 事件时序。
- [ ] T011 [US2] 新增 `WorkspaceWatcher`（`oryxos-core/.../agent/WorkspaceWatcher.java`）：构造注入 `AgentLifecycleService` + agentsDir；`start()` 注册 `WatchService` 起 daemon 线程跑事件循环；包级 `handleChange(Path, WatchEvent.Kind<?>)`：CREATE/MODIFY→`lifecycle.register`、DELETE→`lifecycle.unregisterByDir`，坏目录 try/catch WARN 跳过。`kind()` 用等值比较。让 T010 转绿。
- [ ] T012 [US2] `AgentLifecycleService`：加 `unregisterByDir(Path agentDir)`（按目录名找 Profile → `unregisterProfile` + `remove`，不归档——目录已被手工删）。
- [ ] T013 [US2] 装配层 `OryxOsRuntime`：`WorkspaceWatcher` @Bean(initMethod="start")，守护线程（同 `ThreadPoolTaskScheduler`）。

---

## Phase 5: User Story 3 — 一句话生成一个 Agent (P2)

**Goal**：`POST /agents/generate` 一句话→LLM→AGENT.md 草稿，只返回、不落盘不注册；非法→400。

**Independent Test**：mock `ProviderService` 返回一段 markdown → generate 校验可解析、`agentStore` 无写、`profileRegistry` 无变化；返回非法 markdown → 400 可读原因。

- [ ] T014 [P] [US3] `GenerateTest`（`oryxos-core/src/test/.../agent/GenerateTest.java`）：`generate_producesParsableDraft`（mock chat 返回合法 AGENT.md → 可被 `AgentMarkdown+deriveProfile` 解析）、`generate_neitherWritesNorRegisters`、`generate_invalidDraft_throwsReadable`（非法→`IllegalArgumentException`）。
- [ ] T015 [US3] `AgentLifecycleService.generate(sentence)`：常量 `AGENT_AUTHOR_PROMPT`（AGENT.md 格式说明）；构造生成用 `Profile`（provider/model=注入配置）+ `ProviderRequest.of(AGENT_AUTHOR_PROMPT + 一句话)`→`providerService.chat(genSessionId, 生成Profile, req).text()`；用 `AgentMarkdown.split`+校验能否解析，非法→IAE；返回文本、不落盘不注册。让 T014 转绿。
- [ ] T016 [P] [US3] `AgentApiControllerTest` 加 `generate` 用例：200 返回草稿字符串、LLM 非法→400。
- [ ] T017 [US3] `AgentApiController` 加 `POST /agents/generate` + DTO `GenerateRequest`；装配层确认 generate provider/model 注入已就位（T009/T004）。

---

## Phase 6: User Story 4 — 查 / 改 / 删（删除时序安全） (P2)

**Goal**：update 改定时先注销后注册；delete 恒序 注销定时→移索引→归档；不存在→404。

**Independent Test**：`InOrder` 钉 delete 顺序；update 改 schedules 钉先 unregister 后 register；查改删不存在→404。

- [ ] T018 [P] [US4] `AgentLifecycleServiceTest` 加：`delete_unregistersThenRemovesThenArchives`（`InOrder(agentScheduler, profileRegistry, agentStore)`）、`delete_unknown_throwsNotFound`、`update_scheduleChanged_unregistersBeforeRegister`（`InOrder`）。
- [ ] T019 [US4] `AgentLifecycleService`：`delete(name)`（get 无→`ResourceNotFoundException`；`unregisterProfile`→`remove`→`archive`）、`update(name, md)`（覆写目录；schedules 变则先 `unregisterProfile(old)` 再 deriveProfile+`registerProfile(new)`）。让 T018 转绿。
- [ ] T020 [P] [US4] `AgentApiControllerTest` 加 `update`/`delete` 用例：200 / 不存在 404。
- [ ] T021 [US4] `AgentApiController` 加 `PUT /agents/{name}`、`DELETE /agents/{name}` + DTO `UpdateAgentRequest`。

---

## Phase 7: User Story 5 — 工作区文件浏览器 + 管理台 (P3)

**Goal**：只读列 agents/archive 目录树、读文件；防目录穿越；管理台两页。

**Independent Test**：`tree` 返回结构；`file?path=../../etc/passwd`→400；合法 path→内容。

- [ ] T022 [P] [US5] `WorkspaceApiControllerTest`（`oryxos-web/src/test/.../controller/WorkspaceApiControllerTest.java`）：`tree_returnsAgentsAndArchive`、`file_pathTraversal_returns400`（`../../etc/passwd`）、`file_validPath_returnsContent`。standalone MockMvc。
- [ ] T023 [US5] 新增 `WorkspaceApiController`（`oryxos-web/.../controller/WorkspaceApiController.java`）：`GET /api/v1/workspace/tree`（列 agents/+archive/ 为 `FileNode` 树）、`GET /api/v1/workspace/file?path=`（`normalize()`+`startsWith(oryxosRoot)` 越界→`IllegalArgumentException`(400)，否则只读返回文本）；新增 DTO `FileNode`。让 T022 转绿。
- [ ] T024 [US5] 装配层 `OryxOsRuntime`：`WorkspaceApiController` @Bean（注入 oryxosRoot）。
- [ ] T025 [P] [US5] 前端 `oryxos-web/src/main/frontend/src/App.vue`：加"Agent 管理"页（列表 `GET /agents` + 查看/编辑/删除 + 一句话新建：`POST /agents/generate`→预览可改→`POST /agents`）与"工作区（文件浏览器）"页（`GET /workspace/tree` 目录树 + 点文件 `GET /workspace/file` 只读展示）；复用 26 节管理台风格。

---

## Phase 8: Polish & 跨节回归

- [ ] T026 全量门禁：`mvn clean verify` 全绿（P3C/SpotBugs/FindSecBugs/PMD）；前序节回归绿（29 `AgentLoader`/`ProfileRegistry`/`AgentScheduler`、26 web）；H4 六条自查（generate 落 `llm_calls`、无明文 key、无 Reactor/`CompletableFuture`（WatchService 守护线程属基础设施）、无 Spring AI 自动工具执行、防目录穿越、session_id 只在 SessionManager）。

---

## Dependencies & 执行顺序

- Setup(T001) → Foundational(T002 AgentStore, T003 unregisterProfile, T004 GenerateProperties) → 各故事。
- T002/T003 阻塞 US1 的 `AgentLifecycleService`(T006) 与 US4 的 delete/update(T019)。
- US1(P1) MVP：create 即上线。US2(P1) 依赖 US1 的 `register`（同一段代码）。US3/US4/US5 各自独立叠加。
- `AgentLifecycleService` 是一个类，跨 US1/US2/US3/US4 逐步加方法（create/register/get→unregisterByDir→generate→update/delete）；`AgentLifecycleServiceTest` 同理跨 T005/T018 加用例。

## Parallel 机会

- Foundational：T002、T004 可并（不同文件）。
- 各故事 harness `[P]`：T005、T007、T010、T014、T016、T018、T020、T022、T025 可并写（不同测试/前端文件）。
- 实现任务多改同一批 core/web 类，串行为宜。

## MVP

**US1（Phase 3）** = 最小可交付：`POST /agents` 建一个 Agent 即上线。US2 丢目录即上线、US3 一句话生成、US4 查改删、US5 文件浏览器 + 管理台，增量叠加。

## 格式校验

所有任务 `- [ ] Txxx [P?] [US?] 描述 + 文件路径`；Setup/Foundational/Polish 无 Story 标签，US 阶段带 `[US1..5]`。
