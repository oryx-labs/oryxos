# Tasks: Provider 抽象 + ReAct 循环

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Branch/Dir**: `specs/001-provider-react-loop`

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[US1/US2/US3]**: 归属的用户故事；Setup/Foundational/Polish 无故事标签
- 每个任务标注确切文件路径

## Path Conventions

单体多模块，复用既有 9 模块骨架。核心抽象落 `oryxos-core`，Provider 落 `oryxos-provider`，
审计落 `oryxos-storage`，交互落 `oryxos-channel-cli` + `oryxos-cli`。测试在各模块 `src/test/java`
（JUnit5 + Mockito，fake ChatModel / fake ToolExecutor）。

---

## Phase 1: Setup (Shared Infrastructure)

- [ ] T001 [P] 确认/补充依赖：`oryxos-core` 加 SnakeYAML、Jackson databind；`oryxos-provider` 确认 Spring AI `spring-ai-core` 与 alibaba starter 可用（编辑 `oryxos-core/pom.xml`、`oryxos-provider/pom.xml`）
- [ ] T002 [P] 建包目录骨架：`oryxos-core/src/main/java/io/oryxos/core/{session,profile,context,tool,react}/`（放各自 `package-info.java` 占位）
- [ ] T003 手工建表脚本 `oryxos-storage/src/main/resources/schema.sql`：`llm_calls`、`tool_invocations`（按 data-model.md，不依赖 hibernate ddl 迁移，原则 VIII）
- [ ] T004 [P] 示例默认 Profile `.oryxos/profiles/default.yaml`（`provider.api_key: ${DEEPSEEK_API_KEY}`，含 `settings.max_iterations`/`max_history_turns`）

---

## Phase 2: Foundational (Blocking Prerequisites)

**目的**：所有用户故事共享的模型、Provider 调用、审计、Prompt 组装、工具执行抽象。MUST 先于 Phase 3+ 完成。

- [ ] T005 [P] `Profile` 及其嵌套配置（identity/provider/settings）in `oryxos-core/src/main/java/io/oryxos/core/profile/Profile.java`（按 data-model.md）
- [ ] T006 `ProfileLoader`：YAML 解析 + `${ENV_VAR}` 解析 + 必填/合法性校验（provider 名、model、凭证非空）in `oryxos-core/src/main/java/io/oryxos/core/profile/ProfileLoader.java`（依赖 T005，FR-006/FR-007）
- [ ] T007 [P] `Message`（role/content/toolName/toolCallId/arguments）in `oryxos-core/src/main/java/io/oryxos/core/session/Message.java`
- [ ] T008 `Session`（sessionId/profileName/messages/status/createdAt）in `oryxos-core/src/main/java/io/oryxos/core/session/Session.java`（依赖 T007）
- [ ] T009 `SessionManager` 接口 + `InMemorySessionManager`（ConcurrentHashMap，会话隔离）in `oryxos-core/src/main/java/io/oryxos/core/session/`（依赖 T008，Clarify Q1 / FR-011）
- [ ] T010 [P] `LlmCall` JPA 实体（含 `success`/`errorMessage`，与 tool_invocations 对称，FR-008）in `oryxos-storage/src/main/java/io/oryxos/storage/LlmCall.java`
- [ ] T011 [P] `ToolInvocation` JPA 实体 in `oryxos-storage/src/main/java/io/oryxos/storage/ToolInvocation.java`
- [ ] T012 [P] `LlmCallRepository`（Spring Data JPA）in `oryxos-storage/src/main/java/io/oryxos/storage/LlmCallRepository.java`（依赖 T010）
- [ ] T013 [P] `ToolInvocationRepository` in `oryxos-storage/src/main/java/io/oryxos/storage/ToolInvocationRepository.java`（依赖 T011）
- [ ] T014 `ProviderService`：`Map<String,ChatModel>` 显式映射 + `call(session,profile,prompt)` + 调用后写 `llm_calls`（成功/失败都写，含 success/errorMessage）in `oryxos-provider/src/main/java/io/oryxos/provider/ProviderService.java`（依赖 T012；契约见 contracts/provider-service.md，原则 II/III/V）
- [ ] T015 [P] `ContextLoader`：组装 system prompt（identity + bootstrap）in `oryxos-core/src/main/java/io/oryxos/core/context/ContextLoader.java`（原则 IV）
- [ ] T016 [P] `ToolRegistry` 接口 + 空实现 `EmptyToolRegistry` in `oryxos-core/src/main/java/io/oryxos/core/tool/ToolRegistry.java`（本特性空注册表，R4）
- [ ] T017 `PromptBuilder`：组装 [system + 裁剪历史(max_history_turns) + 工具 schema] → `Prompt` in `oryxos-core/src/main/java/io/oryxos/core/context/PromptBuilder.java`（依赖 T015/T016/T008，FR-010）
- [ ] T018 `ToolExecutor`：execute + 无论成败写 `tool_invocations` + 预留 SandboxChecker 接缝 in `oryxos-core/src/main/java/io/oryxos/core/tool/ToolExecutor.java`（依赖 T013/T016；契约见 contracts/tool-executor.md，原则 V/VI）

**Checkpoint**: 模型/Provider/审计/Prompt/工具执行抽象就位，用户故事可开始。

---

## Phase 3: User Story 1 - 与 Agent 多轮对话并得到最终回答 (Priority: P1) 🎯 MVP

**Goal**: `oryxos chat` 多轮对话，Agent 结合上文连贯回答直到退出。
**Independent Test**: 启动 `oryxos chat`，≥2 轮纯对话（无工具），Agent 记住同会话上文并连贯回答。

- [ ] T019 [US1] `ReActLoop` 核心「无工具」路径：组装 Prompt → `ProviderService.call` → 无 tool call 即追加 ASSISTANT 并返回；迭代上限守卫（默认 10）优雅收尾 in `oryxos-core/src/main/java/io/oryxos/core/react/ReActLoop.java`（依赖 T014/T017，FR-003/SC-005）
- [ ] T020 [US1] `AgentService` 门面：取/建 Session、校验 Profile、委托 ReActLoop in `oryxos-core/src/main/java/io/oryxos/core/AgentService.java`（依赖 T009/T006/T019）
- [ ] T021 [US1] `ChatChannel` 同步 REPL：读输入 → AgentService → 打印；`exit`/`quit`/EOF 干净退出 in `oryxos-channel-cli/src/main/java/io/oryxos/channel/cli/ChatChannel.java`（依赖 T020，FR-001/US1-AS3）
- [ ] T022 [US1] `ChatCommand`（`oryxos chat [--profile]`）并注册进 `OryxOsCli` 的 subcommands in `oryxos-cli/src/main/java/io/oryxos/cli/command/ChatCommand.java` + 编辑 `oryxos-cli/src/main/java/io/oryxos/cli/OryxOsCli.java`（依赖 T021）
- [ ] T023 [P] [US1] 测试：`AgentServiceTest` 两轮输入时第二轮历史含第一轮、两个 sessionId 隔离（fake ReActLoop）in `oryxos-core/src/test/java/io/oryxos/core/AgentServiceTest.java`（US1-AS2/FR-011）
- [ ] T024 [P] [US1] 测试：`ReActLoopTest#noToolReturnsFinalText`（fake ProviderService 返回无 tool call）in `oryxos-core/src/test/java/io/oryxos/core/react/ReActLoopTest.java`

**Checkpoint**: US1 可独立运行与验收——MVP 达成（纯多轮对话）。

---

## Phase 4: User Story 2 - ReAct 循环支持工具调用调度机制 (Priority: P1)

**Goal**: 循环能「检测 tool call → 执行 → 回填 → 继续」，工具恰好执行一次，多步整合，失败回填不崩溃。
**Independent Test**: 注入 fake ToolExecutor + 含 tool call 的 fake 模型响应，验证调度闭环；全程无真实工具。

- [ ] T025 [US2] 扩展 `ReActLoop`：检测响应中的 tool call → 逐个 `ToolExecutor.execute`（恰好一次）→ 结果作 TOOL_RESULT 回填 → 继续；失败结果回填不抛出 in `oryxos-core/src/main/java/io/oryxos/core/react/ReActLoop.java`（依赖 T019/T018，FR-002/FR-004/FR-009）
- [ ] T026 [P] [US2] 测试替身：`FakeChatModel`（可脚本化输出 tool call 序列）、`FakeToolExecutor` in `oryxos-core/src/test/java/io/oryxos/core/testsupport/`
- [ ] T027 [US2] 测试：`ReActLoopTest` 补齐 T1–T5（单步调用/连续两步/超限收尾/失败回填）in `oryxos-core/src/test/java/io/oryxos/core/react/ReActLoopTest.java`（依赖 T026，contracts/react-loop.md）
- [ ] T028 [P] [US2] 测试：`ToolExecutorTest` 成功/失败/未知工具都写 `tool_invocations` in `oryxos-core/src/test/java/io/oryxos/core/tool/ToolExecutorTest.java`（原则 V）

**Checkpoint**: US1 + US2 均可独立验证；ReAct 工具调度机制以测试替身证明成立。

---

## Phase 5: User Story 3 - 通过配置切换模型 Provider (Priority: P2)

**Goal**: 改 Profile 即换 Provider，零代码；多 Provider 同实例并存、路由正确；配置错误清晰报错。
**Independent Test**: 两个指向不同 Provider 的 Profile 分别对话均正常；错误配置启动报清晰错误。

- [ ] T029 [US3] Provider 装配：按已配置凭证生成具名 `ChatModel` bean 并构建 `Map<String,ChatModel>` 注入 ProviderService in `oryxos-provider/src/main/java/io/oryxos/provider/ProviderConfig.java`（依赖 T014，原则 III）
- [ ] T030 [US3] 启动期配置校验：未知 provider 名 / 缺失凭证 → 指向具体配置项的清晰错误、非零退出 in `oryxos-provider/.../ProviderService.java` + `oryxos-core/.../profile/ProfileLoader.java`（依赖 T029/T006，FR-007/US3-AS2）
- [ ] T031 [P] [US3] 测试：`ProviderServiceTest` 两个 name 路由到各自 fake ChatModel；未知 name 抛含该名的清晰异常 in `oryxos-provider/src/test/java/io/oryxos/provider/ProviderServiceTest.java`（US3-AS1/AS2）

**Checkpoint**: 三个用户故事均独立可用。

---

## Phase N: Polish & Cross-Cutting Concerns

- [ ] T032 [P] 更新 `.oryxos/profiles/` 示例与 `README.md` 中 `oryxos chat` 用法片段
- [ ] T033 运行 `mvn clean verify`（JDK 21）确保 Spotless/P3C/Checkstyle/SpotBugs 全绿，修复门禁问题
- [ ] T034 [P] 按 [quickstart.md](./quickstart.md) 跑通场景 2–4 单元测试并核对审计条数（SC-004）；场景 1 记录手动验证结论

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，可立即开始。
- **Foundational (Phase 2)**: 依赖 Setup；**阻塞所有用户故事**。
- **User Stories (Phase 3–5)**: 均依赖 Foundational 完成。
- **Polish (Phase N)**: 依赖所有目标用户故事完成。

### User Story Dependencies

- **US1 (P1)**: Foundational 后即可开始，独立可测（MVP）。
- **US2 (P1)**: 依赖 US1 的 `ReActLoop.java`（T019 → T025 同文件扩展）；其余测试独立。
- **US3 (P2)**: Foundational 后即可开始，与 US1/US2 独立，可并行。

### Within Each Story

模型 → 服务 → 接口/CLI → 测试；同文件任务顺序执行（无 [P]）。

### Parallel Opportunities

- Phase 2 中标 [P] 的任务（T005/T007、T010–T013、T015/T016）可并行，随后 T006/T008/T009/T014/T017/T018 收敛。
- US3（Phase 5）可与 US2（Phase 4）并行开发（不同文件）。
- 各故事内标 [P] 的测试任务可并行编写。

## Parallel Example: Foundational

```text
# 并行启动（不同文件、无相互依赖）：
T005 Profile.java   |  T007 Message.java  |  T010 LlmCall.java  |  T011 ToolInvocation.java
T015 ContextLoader.java  |  T016 ToolRegistry.java
# 收敛后：T006→(T008→T009), T012/T013→T014, (T015/T016/T008)→T017, (T013/T016)→T018
```

## Implementation Strategy

### MVP First (User Story 1 Only)

1. 完成 Phase 1 Setup。
2. 完成 Phase 2 Foundational（关键：阻塞所有故事）。
3. 完成 Phase 3（US1）→ 得到可用的多轮对话 MVP。
4. 独立验收 US1（quickstart 场景 1）后再叠加 US2、US3。

### Incremental Delivery

US1（多轮对话）→ US2（工具调度机制，测试替身）→ US3（Provider 切换）→ Polish。每个 Checkpoint 都是一个可独立验证的增量。

## 任务总数

34 个任务：Setup 4 · Foundational 14 · US1 6 · US2 4 · US3 3 · Polish 3。
