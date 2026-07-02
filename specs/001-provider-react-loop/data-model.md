# Phase 1 Data Model: Provider 抽象 + ReAct 循环

实体来自 spec 的 Key Entities，结合 Clarifications（会话仅内存、审计落库）与宪法约束。
本特性内：**会话/消息/Profile 为内存态**；**LlmCall / ToolInvocation 落 SQLite**。

## Session（内存态）

一次持续的多轮对话。

| 字段 | 类型 | 说明 |
|------|------|------|
| `sessionId` | String | 唯一标识（channel + user + profile 派生或 UUID） |
| `profileName` | String | 关联的 Agent Profile |
| `messages` | List<Message> | 按时间顺序的对话历史 |
| `status` | enum `ACTIVE`/`ENDED` | 会话状态 |
| `createdAt` | Instant | 创建时间 |

- **生命周期**: `ACTIVE` →（用户退出 / 进程结束）→ `ENDED`。进程退出即丢失（Clarify Q1）。
- **隔离**: 由 `SessionManager` 以 `sessionId` 为 key 管理，各会话独立 `messages`（FR-011）。
- **裁剪**: 组装 Prompt 时按 `max_history_turns`（默认 20）保留最近若干轮（FR-010）。

## Message（内存态）

会话历史中的一条记录。

| 字段 | 类型 | 说明 |
|------|------|------|
| `role` | enum `USER`/`ASSISTANT`/`TOOL_CALL`/`TOOL_RESULT` | 消息角色 |
| `content` | String | 文本内容（TOOL_RESULT 为工具输出的序列化） |
| `toolName` | String? | 仅 TOOL_CALL/TOOL_RESULT：目标工具名 |
| `toolCallId` | String? | 关联一次工具调用请求与其结果 |
| `arguments` | JSON? | 仅 TOOL_CALL：调用参数 |

- **状态流转**: 一次 ReAct 迭代可产生 `ASSISTANT`(含 tool_call) → `TOOL_RESULT` → 下一轮
  `ASSISTANT`；最终回答为不含 tool_call 的 `ASSISTANT`。

## Profile（内存态，来自 YAML）

一个 Agent 的完整定义。本特性只用到与对话/Provider/循环相关的子集。

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | String | Profile 名（`oryxos chat --profile` 引用） |
| `identity.agentName` | String | Agent 名 |
| `identity.prompt` | String | 人格/system prompt 主体 |
| `provider.name` | String | 对应 ProviderService 映射 key |
| `provider.model` | String | 模型名 |
| `provider.temperature` | double? | 采样温度 |
| `provider.apiKey` | String | `${ENV_VAR}` 占位，运行时从环境变量解析（FR-006） |
| `tools` | List<String> | 可用工具名（本特性通常为空） |
| `settings.maxIterations` | int (默认 10) | ReAct 迭代上限（FR-003） |
| `settings.maxHistoryTurns` | int (默认 20) | 历史保留轮次（FR-010） |

- **校验**（FR-007，`ProfileLoader`）: `name`/`provider.name`/`provider.model` 必填；
  `provider.name` 必须在 ProviderService 映射中存在；`apiKey` 环境变量必须已解析出非空值；
  缺失/非法 → 抛出指向具体字段的清晰错误，不静默失败。

## LlmCall（SQLite：`llm_calls`，审计，Day One）

一次模型调用的审计记录（原则 V）。

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT PK | 主键 |
| `sessionId` | VARCHAR | 关联会话 |
| `provider` | VARCHAR | Provider 名 |
| `model` | VARCHAR | 模型名 |
| `promptTokens` | INT | 输入 token |
| `completionTokens` | INT | 输出 token |
| `totalTokens` | INT | 合计 token |
| `success` | BOOLEAN | 调用是否成功（FR-008「成败」，与 tool_invocations 对称） |
| `errorMessage` | TEXT? | 失败原因（超时/限流/报错），成功时空 |
| `durationMs` | BIGINT | 调用耗时 |
| `createdAt` | TIMESTAMP | 调用时间 |

- 由 `ProviderService` 在每次 `chatModel.call` 后同步写入；调用成功与失败（超时/限流/报错）都写一条。

## ToolInvocation（SQLite：`tool_invocations`，审计，Day One）

一次工具执行的审计记录（原则 V）。本特性无真实工具，但表与写入路径 Day One 就绪；由 fake
执行器在测试中验证写入。

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT PK | 主键 |
| `sessionId` | VARCHAR | 关联会话 |
| `toolName` | VARCHAR | 工具名 |
| `inputJson` | TEXT | 调用参数 |
| `resultJson` | TEXT | 执行结果 |
| `success` | BOOLEAN | 是否成功 |
| `errorMessage` | TEXT? | 错误信息（可空） |
| `durationMs` | BIGINT | 执行耗时 |
| `createdAt` | TIMESTAMP | 调用时间 |

- 由 `ToolExecutor` 在每次执行后同步写入（成功与失败都写）。
- 建表脚本手工维护，不依赖 Hibernate 自动迁移（原则 VIII）。

## ToolResult（既有值对象，内存）

`ToolExecutor.execute` 的返回：`success` / `content` / `errorMessage` / `retryable`。
`retryable` 作为失败时回填给模型的提示（R8）。

## 关系图（简）

```text
Profile 1───* Session 1───* Message
Session 1───* LlmCall          (审计)
Session 1───* ToolInvocation   (审计)
ToolExecutor ──produces──> ToolResult ──backfill──> Message(TOOL_RESULT)
```
