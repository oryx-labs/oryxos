# Phase 0 Research: Provider 抽象 + ReAct 循环

本文件记录进入设计前需要拍板的技术决策。每条含：Decision / Rationale / Alternatives considered。

## R1. 如何在禁用自动工具执行的前提下用 Spring AI 拿到 tool call

**Decision**: 用 `chatModel.call(new Prompt(messages, ChatOptions))` 直接调用底层 `ChatModel`，
在 `ToolCallingChatOptions`（或等价选项）里声明工具的 JSON Schema（由 `@Tool`/schema 生成），
但**关闭内部工具执行代理**。从返回的 `ChatResponse` 的 assistant message 里读取 tool call
列表，由 `ReActLoop` 自己决定执行。绝不使用 `ChatClient.prompt().tools(...).call()` 这类会
自动执行工具的高层 API。

**Rationale**: 宪法原则 I、II——只借 Spring AI 做协议转换与 schema 生成，循环与工具调度自持。
高层 `ChatClient` 会在内部跑工具并二次调模型，导致工具被调两次、循环失控。

**Alternatives considered**:
- `ChatClient` + 自动 tool 执行：被原则 II 明确禁止。
- 完全自己拼各家 HTTP 协议：重复造轮子，放弃 Spring AI 的协议适配价值，违背"复用管道"。

## R2. Provider 显式映射的构建方式

**Decision**: `ProviderService` 内部维护 `Map<String, ChatModel>`，key = Profile 里的
provider name（如 `deepseek`/`qwen`/`kimi`）。映射在启动时按"已配置凭证"的 Provider 显式装配
（每个 Provider 一个具名 `ChatModel` bean，注入到 map），调用时按 name 查表路由。

**Rationale**: 原则 III——多 Provider 的 `ChatModel` bean 类型相同，靠类型扫描会路由错乱；
显式 name→model 是唯一可靠方式。

**Alternatives considered**:
- 按 Bean 类型 `@Autowired List<ChatModel>`：类型相同无法区分，违背原则 III。
- 按 `@Qualifier` 注入：可行但仍需一张显式表来做运行时 name 路由，等价于本方案。

## R3. 本特性支持哪些 Provider（Clarify 里 Deferred 的问题）

**Decision**: 机制上支持任意 N 家；**默认只要求 1 家可跑通**（凭证已配置的那家，如 DeepSeek）
即满足 US1/US2。US3「切换 Provider」的验收用**显式映射的路由单元测试 + 两个 Profile 的配置级
验证**完成，不强制两家都有真实凭证（无凭证的 Provider 在映射缺失时给清晰错误即可）。

**Rationale**: 不让"必须持有多家真实 key"阻塞进度；显式映射的正确性可用测试替身验证，符合
"分阶段克制"。真实多家联调可在有凭证时随时补。

**Alternatives considered**:
- 强制接入 ≥2 家真实 Provider：受限于凭证可得性，且不增加机制正确性的置信度。

## R4. ReAct 循环的工具执行器抽象（本特性不交付真实工具）

**Decision**: 定义 `ToolExecutor`（对 `OryxTool` 的执行 + 写 `tool_invocations` 审计 + 预留
`SandboxChecker` 校验接缝）与 `ToolRegistry`（name→OryxTool 查询，本特性可为空注册表）。
`ReActLoop` 只依赖这两个抽象；本特性用 **fake `ToolExecutor`** 在测试中驱动"检测→执行→回填→
继续"。真实内置/MCP 工具在后续工具特性填充 `ToolRegistry`。

**Rationale**: Clarify Q2——本特性只实现调度机制。抽象先行使后续工具特性零改动接入（原则 IV
"工具三合一"、架构约束"新增不改 core"）。

**Alternatives considered**:
- 本特性直接内置一个天气工具：被 Clarify Q2 否决（不做演示工具）。
- 不定义 `ToolExecutor`，把执行内联进 ReActLoop：后续接工具要改核心循环，破坏解耦。

## R5. 会话状态模型（Clarify Q1：仅内存）

**Decision**: `SessionManager` 为接口，本特性提供 `InMemorySessionManager`（`ConcurrentHashMap`
按 sessionId 存 `Session`，每会话独立 `Message` 列表）。进程退出即丢。历史裁剪按
`max_history_turns`（默认 20）保留最近若干轮。并发会话靠 map + 每会话独立列表天然隔离。

**Rationale**: Clarify Q1。接口化让第三周存储特性替换为 SQLite 实现时不改调用方（原则 VIII
"状态外置"预留）。

**Alternatives considered**:
- 直接写 SQLite：被 Clarify Q1 否决（越界到第三周）。
- 全局单会话：无法满足 FR-011 并发隔离。

## R6. 审计落库（原则 V，Day One）

**Decision**: `oryxos-storage` 用 Spring Data JPA + SQLite 建 `llm_calls`、`tool_invocations`
两表。写入同步进行：`ProviderService` 每次模型调用后写 `llm_calls`；`ToolExecutor` 每次执行后写
`tool_invocations`。建表脚本手工维护（`schema.sql` 或 Flyway），**不依赖 `hibernate.ddl-auto`
迁移**（SQLite ALTER 支持弱，原则 VIII）。本特性无查询接口，只保证写入。

**Rationale**: 原则 V——审计核心阶段就落库，不靠日志反解析。

**Alternatives considered**:
- 只打日志：违背原则 V。
- `ddl-auto=update` 建表：SQLite ALTER 支持弱，改用手工脚本/Flyway。

## R7. 迭代上限与终止（FR-003）

**Decision**: `ReActLoop` 每轮自增计数，达到 Profile `settings.max_iterations`（默认 10）仍无
最终回答时，终止并返回一条面向用户的说明（如"未能在限定步数内完成，请缩小问题范围"）。
"最终回答" = 模型返回 assistant message 且无 tool call。

**Rationale**: FR-003、SC-005，防无限循环。默认 10 来自 CLAUDE.md。

**Alternatives considered**:
- 无上限：违背 FR-003。
- 超限抛异常：对用户不友好；改为优雅收尾返回说明。

## R8. 工具失败处理（FR-009）

**Decision**: `ToolResult.retryable` 作提示；`ReActLoop` **不做自动重试**，而是把失败结果作为
tool_result 消息回填进对话历史，让模型自行决定重试或向用户说明。单次工具失败不抛出、不中断会话。

**Rationale**: 保持循环控制权在模型的推理里（ReAct 精神）；避免框架层隐式重试放大副作用。
与 Clarify 一致。

**Alternatives considered**:
- 循环层自动重试 N 次：可能对非幂等工具造成重复副作用；留给未来按工具声明配置。

## R9. CLI 交互形态（FR-001）

**Decision**: `oryxos chat [--profile <name>]`（Picocli 子命令）启动同步 REPL：读取一行输入 →
交 `AgentService.chat(sessionId, input)` → 打印最终回答 → 循环；输入退出指令（如 `exit`/`quit`
或 EOF）干净退出。单次运行一个内存会话。

**Rationale**: FR-001、US1；同步阻塞契合原则 VII。

**Alternatives considered**:
- 一次性单轮命令：不满足"多轮对话"。
- 流式输出（SSE/打字机）：放扩展阶段，避免引入异步。

## 未决 / 移交后续特性

- 真实内置工具、MCP Client、`SandboxChecker` 具体校验 → 工具特性（第二周）。
- 会话/审计的查询接口、跨重启恢复 → 存储 + Web 特性（第三周）。
- 长期记忆（MEMORY.md）→ 记忆特性（第二周）。
