# Tasks: Provider——对接大模型的统一入口（第16节）

**Input**: Design documents from `/specs/001-provider-abstraction/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/provider-service.md

**Tests**: 用户显式要求 harness 先行——每个实现任务的测试任务紧邻其前（红→绿）；课件三个中文名关键回归测试必须原样落地。

**Organization**: 按用户故事分组；US1 为 MVP。

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

- [x] T001 在 oryxos-provider/pom.xml 新增 `org.springframework.ai:spring-ai-openai`（版本由根 pom BOM 管理），跑 `mvn -pl oryxos-provider -am dependency:resolve` 确认 1.0.0-M6 解析成功（H3 实核）
- [x] T002 【停点已确认项】给 oryxos-core/src/main/java/io/oryxos/core/OryxTool.java 补 `String getInputSchema();`（research D4；出处 TechSol §6.1 + 20 节课件预告）
- [x] T003 【写前 H3 核实】解包本地 `spring-ai-openai-1.0.0-M6.jar`，确认三件事的确切 API：`OpenAiApi` 构造（baseUrl+apiKey）、`OpenAiChatModel` 构造、`OpenAiChatOptions` 上关闭自动执行的开关（候选 `proxyToolCalls`）与工具描述挂载方式（候选 `functionCallbacks`）；把结论追记进 specs/001-provider-abstraction/research.md 的 D1/D2 条目——核实不到 → 软门禁停下报告

## Phase 2: Foundational（阻塞所有故事）

- [x] T004 [P] 创建 Profile 记录类族于 oryxos-core/src/main/java/io/oryxos/core/profile/：`Profile`（含嵌套 `Identity`/`ProviderRef`/`NotifyChannel`/`ScheduleConfig`/`Settings`，字段按 data-model.md 全量建齐，record 不可变，`Settings` 缺省 maxIterations=10/maxHistoryTurns=20）+ `ProfileValidationException`
- [x] T005 [P] 先写测试 oryxos-core/src/test/java/io/oryxos/core/profile/ProfileLoaderTest.java：①合法 YAML 全字段解析（含蛇形→驼峰）②引用不存在的 provider 名报错信息含该名字 ③坏 YAML 文件被跳过、其余 Profile 正常加载（SC-007）④`${ENV}` 占位从环境变量解析 ⑤加载后可从 ProfileRegistry 按 name 查到（用 @TempDir 造 profiles 目录）
- [x] T006 实现 oryxos-core/src/main/java/io/oryxos/core/profile/ProfileLoader.java（SnakeYAML 扫描 `.oryxos/profiles/*.yaml`；构造注入 `Set<String> knownProviders` 做 provider 名校验——core 不得反向依赖 provider 模块；坏文件记 ERROR 跳过）与 ProfileRegistry.java（`Map<String,Profile>` 内存索引，`get`/`all`），使 T005 全绿

**Checkpoint**: Profile 体系可用，三个故事可开工。

## Phase 3: User Story 1 - 多 Provider 精确路由 + 只翻译不执行（P1）🎯 MVP

**Goal**: `chat(sessionId, Profile, ProviderRequest)` 按名路由零串台；工具 schema 翻译挂载、自动执行关闭、tool call 原样透传。

**Independent Test**: `ProviderServiceTest` + `ToolSchemaAdapterTest` 全绿（全 mock，无网络）。

- [x] T007 [P] [US1] 创建值对象与异常于 oryxos-provider/src/main/java/io/oryxos/provider/：`ProviderRequest`（content + `List<OryxTool>` availableTools）、`ProviderResponse`（text + `List<ToolCallRequest>` + `Usage`）、`ToolCallRequest`（name + argumentsJson）、`Usage`（prompt/completion/total）、`ProviderNotFoundException`（消息含 provider 名）——契约见 contracts/provider-service.md，【停点已确认项 D8】
- [x] T008 [P] [US1] 先写测试 oryxos-provider/src/test/java/io/oryxos/provider/ToolSchemaAdapterTest.java：OryxTool 的 name/description/inputSchema 翻译后字段一一对齐；产物不含任何执行逻辑（纯描述对象）；空工具列表返回空
- [x] T009 [US1] 实现 oryxos-provider/src/main/java/io/oryxos/provider/ToolSchemaAdapter.java（OryxTool → Spring AI 工具描述，按 T003 核实的挂载方式），使 T008 全绿
- [x] T010 [US1] 先写测试 oryxos-provider/src/test/java/io/oryxos/provider/ProviderServiceTest.java（mock ChatModel + mock LlmCallAuditor）：**课件回归①** `按名路由_两个provider不串台`（verify 目标家 times(1)、另一家 never()）；**课件回归③** `带工具schema调用_请求里关闭了自动执行`（ArgumentCaptor 抓请求断言自动执行开关关闭且工具描述已挂载）；未知 provider 名抛 `ProviderNotFoundException` 且消息含名字；model 与 temperature 取自 Profile、temperature 缺省时不设置（D6）
- [x] T011 [US1] 定义审计接口 oryxos-provider/src/main/java/io/oryxos/provider/LlmCallAuditor.java（`record(sessionId, provider, model, Usage, success, errorMessage, durationMs)`），实现 ProviderService.java：显式 `Map<String,ChatModel>` 构造注入、`chat` 按契约路由/翻译/调用/成功审计/返回，使 T010 当前用例全绿
- [x] T012 [US1] 实现 oryxos-provider/src/main/java/io/oryxos/provider/ProvidersProperties.java（`oryxos.providers` 列表：name/api-key/base-url，启动校验：名不重复、env 解析后非空缺失即点名报错）与 ProviderChatModelFactory.java（按配置手工 `new OpenAiApi`/`new OpenAiChatModel` 构造映射表——按 T003 核实的签名；不使用任何 starter 自动装配），并为 Properties 校验补测试用例入 ProviderServiceTest 或独立小节

**Checkpoint**: US1 独立可验——MVP 达成。

## Phase 4: User Story 2 - 成败都留痕的审计（P2）

**Goal**: 每次调用恰好一条 llm_calls；失败先落 success=false 带原因再上抛；审计自身失败 log&continue。

**Independent Test**: `ProviderServiceTest` 失败路径用例 + `LlmCallRepositoryTest` 全绿。

- [x] T013 [US2] 在 ProviderServiceTest.java 补 **课件回归②** `调用失败_审计必须留下success为false的记录`（mock ChatModel 抛异常 → assertThrows 且 verify auditor 收到 success=false + contains 原因）；补"审计自身抛异常时调用结果不受影响"用例（D5）
- [x] T014 [US2] 在 ProviderService.java 补失败路径（catch RuntimeException → 先审计后 rethrow）；auditor 调用外层不包裹——log&continue 语义放实现侧（见 T016），使 T013 全绿
- [x] T015 [P] [US2] 先写测试 oryxos-storage/src/test/java/io/oryxos/storage/LlmCallRepositoryTest.java：`@DataJpaTest` + `ddl-auto=none` + 显式执行 schema.sql + SQLite 文件库（@TempDir）；写入一条 success=true 与一条 success=false 带 error_message，读回字段完整——建表必须走手工脚本
- [x] T016 [US2] 实现 oryxos-storage：src/main/resources/schema.sql（llm_calls 建表，列按 data-model.md）、LlmCall.java 实体、LlmCallRepository.java、JpaLlmCallAuditor.java（实现 LlmCallAuditor；内部 catch 任意异常记 ERROR 日志不上抛——D5），使 T015 全绿

**Checkpoint**: US2 独立可验。

## Phase 5: User Story 3 - 配置即运维（P3）

**Goal**: 换模型零代码、坏配置不拖垮、无明文凭证——大部分行为已由 Phase 2/3 覆盖，此处补齐剩余断言。

**Independent Test**: 下列用例全绿 + grep 无明文（人工项留验收报告）。

- [x] T017 [US3] 在 ProfileLoaderTest.java 补"改 model 字段重新加载后 Profile.provider().model() 生效"用例；在 ProviderServiceTest.java 确认请求里 model 来自 Profile（若 T010 已覆盖则标注引用即可，不重复写）

## Phase 6: Polish & Cross-Cutting

- [x] T018 [P] 编写 oryxos-provider/src/test/java/io/oryxos/provider/ProviderSmokeIT.java：`@Tag("integration")`，读环境变量真 key、真调一次、断言非空响应且 llm_calls +1 success=true；surefire 配置默认排除 integration 组（CI 跳过）
- [x] T019 跑 `mvn clean verify` 修复全部静态检查（Spotless/P3C/Checkstyle/SpotBugs/FindSecBugs/PMD/OWASP）直至全绿——注意语法禁区（P3C 对增强 switch `default ->` 误报）
- [x] T020 H4 六条全局不变量脚本化自查（grep 无明文 key / 无 CompletableFuture/Reactor / 无 ChatClient 自动执行路径），把证据写入验收报告草稿

## Dependencies

- Phase 1 → Phase 2 → US1(Phase 3) → US2(Phase 4) → US3(Phase 5) → Polish。
- US2 依赖 US1 的 ProviderService/LlmCallAuditor 接口；US3 仅补断言，依赖 Phase 2/3。
- T003（API 核实）阻塞 T009/T011/T012（写前 H3）。

## Parallel Examples

- Phase 2：T004 ∥ T005（不同文件）。
- US1：T007 ∥ T008 可并行；T010 可与 T009 并行起草。
- US2：T015 与 T013 并行（不同模块）。

## Implementation Strategy

MVP = Phase 1+2+3（US1）：mock 审计接口即可交付"能路由、不执行工具"的最小闭环；US2 补真实落库；US3 只是断言收口。每完成一个任务跑该模块测试（任务级 DoD），不攒账。
