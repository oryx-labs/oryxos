# Tasks: ReAct 循环——Agent 的大脑

**Input**: Design documents from `/specs/002-react-loop/`（plan.md / research.md D1~D8 / data-model.md / contracts/react-loop.md / quickstart.md）

**Tests**: 课件"验收 harness"明确要求 → 测试任务先于或伴随对应实现任务。测试方法名英文，课件中文原名进 `@DisplayName`（本节起新规）。

**Organization**: 按用户故事分组；物理执行顺序按编译依赖排（US5 底座 → US3 工具链 → US1/US2 主循环 → US4 入口），每阶段独立可测。

## Phase 1: Setup

- [x] T001 记录改造前基线：仓库根执行 `mvn test -pl oryxos-provider,oryxos-storage -am -q` 确认 16 节测试全绿（D1 动手前的对照基线）

## Phase 2: Foundational（D1 契约上移 + D2 前向最小——⚠️ 停点确认项，用户确认后才执行）

**Blocking**: 后续所有故事都依赖本阶段的类型与依赖方向。

- [x] T002 D1 契约上移（core 侧）：新建 `oryxos-core/src/main/java/io/oryxos/core/provider/` 包——`ProviderService.java`（接口，唯一方法 `ProviderResponse chat(String sessionId, Profile profile, ProviderRequest request)` 签名逐字保真）+ `ProviderRequest.java` / `ProviderResponse.java` / `ToolCallRequest.java` / `Usage.java` / `LlmCallAuditor.java`（自 `io.oryxos.provider` 原样移入，仅改包名）
- [x] T003 D1 契约上移（provider 侧）：删除 `oryxos-provider` 中已上移的五个文件；`ProviderService.java` 改名 `SpringAiProviderService.java` 并 `implements io.oryxos.core.provider.ProviderService`（实现体零改动）；修正模块内全部 import（`ToolSchemaAdapter`/`ProviderChatModelFactory`/`ProvidersProperties` 等）；同步修正测试 `ProviderServiceTest`（被测类改 SpringAiProviderService，断言零改动）/`ToolSchemaAdapterTest`/`ProvidersPropertiesTest`/`ProviderSmokeIT` 的 import
- [x] T004 D1 契约上移（storage 侧）：`oryxos-storage/src/main/java/io/oryxos/storage/JpaLlmCallAuditor.java` 改 import core 接口；`oryxos-storage/pom.xml` 移除 `oryxos-provider` 依赖（16 节遗留 flag 就此解决）
- [x] T005 D1 回归门禁（硬）：`mvn test -pl oryxos-provider,oryxos-storage -am` 全绿（16 节断言零改动）；`grep -r "io.oryxos.provider" oryxos-core/src/main` 零命中
- [x] T006 [P] D2 前向最小：新建 `oryxos-core/src/main/java/io/oryxos/core/session/`——`Session.java`（sessionId/profileName/按序消息累积，appendUser/appendAssistant/appendToolResult，对外只读视图）+ `Message.java`（record：role/content/toolName）+ `SessionManager.java`（接口，仅 `void save(Session session)`）

## Phase 3: US5 上下文组装——新鲜、有序、可控长度 (P2，底座先行)

**Goal**: ContextLoader 无缓存供给 system 段；PromptBuilder 四段有序拼接、历史截断、日期时间行。
**Independent Test**: `mvn test -pl oryxos-core -am -Dtest='ContextLoaderTest,PromptBuilderTest'` 全绿。

- [x] T007 [P] [US5] ContextLoaderTest：`oryxos-core/src/test/java/io/oryxos/core/context/ContextLoaderTest.java`（@TempDir 模拟 .oryxos）——改文件后下一次 load 立即读到新内容（无缓存回归）；Skill 引用缺失抛 IllegalStateException 点名文件；Bootstrap 缺失 WARN（Logback ListAppender 断言）且不阻断；identity.prompt+bootstrap+skills 拼接顺序
- [x] T008 [US5] ContextLoader 实现：`oryxos-core/src/main/java/io/oryxos/core/context/ContextLoader.java`（研发决策 D7：构造注入 Path，每次重读，无缓存字段；日志参数 `replace('\r','_').replace('\n','_')` 消毒）
- [x] T009 [US5] PromptBuilderTest：`oryxos-core/src/test/java/io/oryxos/core/agent/PromptBuilderTest.java`——四段顺序正确；历史超 N 轮截断（坑二回归，一轮=一条 user 消息及其后全部消息）；恰好 N 轮不截断；system 段末尾含当前日期时间（Clock.fixed 断言）；availableTools 只含 Profile.tools 点名的工具
- [x] T010 [US5] PromptBuilder 实现：`oryxos-core/src/main/java/io/oryxos/core/agent/PromptBuilder.java`（研发决策 D4：产出 ProviderRequest；记忆段留位恒空；Clock 构造重载）
- [x] T011 [US5] 阶段门禁：`mvn test -pl oryxos-core -am -Dtest='ContextLoaderTest,PromptBuilderTest' -Dsurefire.failIfNoSpecifiedTests=false` 绿，红了当场修

## Phase 4: US3 全程可审计——工具执行链 (P2)

**Goal**: ToolExecutor 唯一执行路径 + tool_invocations 成败都写（宪法 V Day-One）。
**Independent Test**: `ToolExecutorTest` + `ToolInvocationRepositoryTest` 全绿。

- [x] T012 [P] [US3] ToolInvocationAuditor 接口：`oryxos-core/src/main/java/io/oryxos/core/agent/ToolInvocationAuditor.java`（record 七参对应表列，javadoc 注明实现方自吞异常口径——与 16 节 LlmCallAuditor 同构）
- [x] T013 [US3] ToolExecutorTest：`oryxos-core/src/test/java/io/oryxos/core/agent/ToolExecutorTest.java`——成功写审计 success=true 含耗时；工具抛异常写 success=false 带原因、返回失败 ToolResult 不上抛（异常不吞也不中断）；未注册工具名 → 失败结果 + success=false 审计
- [x] T014 [US3] ToolExecutor 实现：`oryxos-core/src/main/java/io/oryxos/core/agent/ToolExecutor.java`（研发决策 D3：Map<String,OryxTool> 构造注入；执行前留沙箱检查位注释注明 24 节 SandboxChecker 接线；先落审计再返回结果）
- [x] T015 [P] [US3] 存储三件：`oryxos-storage/src/main/resources/schema.sql` 追加 `tool_invocations` DDL（含 success BOOLEAN NOT NULL / error_message TEXT / idx_tool_invocations_session）+ `ToolInvocation.java` 实体（@PrePersist createdAt）+ `ToolInvocationRepository.java`（findBySessionId）
- [x] T016 [US3] ToolInvocationRepositoryTest：`oryxos-storage/src/test/java/io/oryxos/storage/ToolInvocationRepositoryTest.java`（16 节 LlmCallRepositoryTest 同款：@DataJpaTest + @TempDir SQLite 文件 + sql.init.mode=always + ddl-auto=none；保存查询/成败双态/手工 DDL 生效三连）
- [x] T017 [US3] JpaToolInvocationAuditor：`oryxos-storage/src/main/java/io/oryxos/storage/JpaToolInvocationAuditor.java`（implements core 接口；写入失败自吞 RuntimeException 记 ERROR 不阻断，日志消毒同 16 节）
- [x] T018 [US3] 阶段门禁：`mvn test -pl oryxos-storage -am` 绿

## Phase 5: US1+US2 主循环——多轮"想—做—看" + 死循环兜底 (P1)

**Goal**: ReActLoop 自实现调度（宪法 I）；停止条件双保险。
**Independent Test**: `ReActLoopTest` 全绿（全 mock 不碰网络）。

- [x] T019 [US1] ReActLoopTest：`oryxos-core/src/test/java/io/oryxos/core/agent/ReActLoopTest.java`——①无工具调用一轮收尾、零工具执行；②首轮要求调工具→执行→结果回填→次轮收尾（恰好 2 次 chat、1 次 execute）；③一轮多工具逐个顺序执行；④每轮响应与工具结果按序累积进 Session（坑三回归）；⑤**关键回归** `modelKeepsRequestingTools_forceStopAtMaxIterations` `@DisplayName("模型一直要调工具_转满最大轮数强制停")`——mock 每轮都返回工具调用，`verify(providerService, times(10)).chat(any(), any(), any())` 恰好 10 轮一轮不多 + `assertTrue(reply.contains("达到最大轮数"))`（断言逻辑照课件逐条保真）；⑥maxIterations=5 的 Profile 恰好 5 轮停；⑦text 为 null 且无工具调用按空串收尾
- [x] T020 [US2] ReActLoop 实现：`oryxos-core/src/main/java/io/oryxos/core/agent/ReActLoop.java`（研发决策 D5：课件骨架逐行同构；强制收尾字面量"达到最大轮数，已停止"；chat/execute 均携带 session.sessionId()）
- [x] T021 [US1] 阶段门禁：`mvn test -pl oryxos-core -am -Dtest='ReActLoopTest' -Dsurefire.failIfNoSpecifiedTests=false` 绿

## Phase 6: US4 统一入口与 Agent 上下文生命周期 (P2)

**Goal**: AgentService 唯一处理入口；ProfileContext ThreadLocal 生命周期钉死。
**Independent Test**: `AgentServiceTest` 全绿。

- [x] T022 [P] [US4] ProfileContext 实现：`oryxos-core/src/main/java/io/oryxos/core/agent/ProfileContext.java`（final 类 + 私有构造；set/current/clear，clear 用 ThreadLocal.remove() 防泄漏）
- [x] T023 [US4] AgentServiceTest：`oryxos-core/src/test/java/io/oryxos/core/agent/AgentServiceTest.java`——①处理期间 ProfileContext.current() 可取到当前 Profile（用 Answer 在 run 内断言）；②**关键回归** `processThrowsException_profileContextMustBeCleared` `@DisplayName("处理中抛异常_ProfileContext也必须被清掉")`——`when(reActLoop.run(any(), any(), any())).thenThrow(new RuntimeException("boom"))` + `assertThrows(RuntimeException.class, ...)` + `assertNull(ProfileContext.current())`（断言逻辑照课件逐条保真）；③正常结束后 `verify(sessionManager).save(session)`；④异常路径不 save（verify never）；⑤Profile 不存在 → 点名报错
- [x] T024 [US4] AgentService 实现：`oryxos-core/src/main/java/io/oryxos/core/agent/AgentService.java`（研发决策 D6：课件骨架逐行同构，set→try{run→save→return}finally{clear}）
- [x] T025 [US4] 阶段门禁：`mvn test -pl oryxos-core -am` 全绿（本节五个测试类 + 16 节 ProfileLoaderTest 回归）

## Phase 7: Polish & 收尾

- [x] T026 全仓硬门禁：`mvn clean verify` 全绿（含 Spotless/P3C/Checkstyle/SpotBugs/FindSecBugs），静态检查红了修实现不改规则
- [x] T027 H4 六条全局不变量自查 + 课件"本节交付物"逐项 ls/grep 存在性核对（结果记入报告）
- [x] T028 验收报告：`specs/002-react-loop/acceptance-report.md`（六项证据 DoD + 剩余人工项清单：真模型 Demo 一对话版、code review 确认循环自实现）

## Dependencies

- Phase 2（T002~T006）阻塞全部故事；T002→T003→T004→T005 串行，T006 可与 T003/T004 并行。
- US5（T007~T011）→ US3（T012~T018；T012/T015 可与 US5 并行）→ US1/US2（T019~T021，编译依赖 PromptBuilder/ToolExecutor/ProviderService 接口/Session）→ US4（T022~T025，依赖 ReActLoop）→ Polish。
- 每个实现任务的测试先于或伴随它：T007<T008、T009<T010、T013<T014、T016 伴随 T015/T017、T019<T020、T023<T024。

## Implementation Strategy

- 物理顺序按编译依赖（底座→工具链→主循环→入口），每阶段结束跑阶段门禁，红了当场修不攒账。
- MVP = Phase 2 + US5 + US3 + US1/US2（主循环可独立演示"想—做—看"与死循环兜底）；US4 补齐入口即达本节全量。
- 并行机会：T006∥T003/T004；T007∥T009（不同文件）；T012∥T015；T022 可提前。
