# Data Model: ReAct 循环——Agent 的大脑

**Date**: 2026-07-10 | **Feature**: [spec.md](./spec.md)

## 领域对象（oryxos-core，非持久化）

### Session（io.oryxos.core.session.Session）——前向最小，18 节补全持久化

| 字段 | 类型 | 说明 |
|---|---|---|
| sessionId | String | 会话标识；本节只透传（拼接规则归 18 节 SessionManager，H4④） |
| profileName | String | 所属 Agent；AgentService 据此查 ProfileRegistry |
| messages | List\<Message\> | 按序累积；对外 `List.copyOf` 只读视图 |

行为：`appendUser(String)` / `appendAssistant(ProviderResponse)`（存 text，text 为 null 时按空串）/ `appendToolResult(String toolName, ToolResult)`（存 content 或 errorMessage）。**顺序不变量**：messages 严格按发生序追加，事后可完整回放（FR-003）。

### Message（record）

| 字段 | 类型 | 说明 |
|---|---|---|
| role | String | `user` / `assistant` / `tool` 三值 |
| content | String | 文本内容（工具结果为 result 或错误描述） |
| toolName | String | 仅 role=tool 非空 |

### 上下文组装产物（ProviderRequest，16 节已有，非持久化、轮轮重建）

`content` = ①system 段（ContextLoader 产出 + 日期时间行）②记忆段（本节恒空）③历史段（最近 N 轮，一轮 = 一条 user 消息及其后全部消息）按序拼接；`availableTools` = Profile.tools 对应的 OryxTool 列表。

## 契约上移（D1，包移动无字段变化）

`io.oryxos.provider` → `io.oryxos.core.provider`：`ProviderRequest`、`ProviderResponse`、`ToolCallRequest`、`Usage`（四个 record 原样）+ `LlmCallAuditor`（接口原样）+ `ProviderService`（具体类抽为接口，唯一方法 `chat(String sessionId, Profile profile, ProviderRequest request)` 签名逐字保真）。

## 持久化实体（oryxos-storage）

### tool_invocations（手工 schema.sql，宪法 V/VIII）

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | INTEGER | PK AUTOINCREMENT | 主键 |
| session_id | VARCHAR | NOT NULL, 索引 idx_tool_invocations_session | 关联会话 |
| tool_name | VARCHAR | NOT NULL | 工具名 |
| input_json | TEXT | | 调用参数 |
| result_json | TEXT | | 执行结果 |
| success | BOOLEAN | NOT NULL | 成败标识——失败也必须有记录 |
| error_message | TEXT | 可空 | 失败原因 |
| duration_ms | BIGINT | | 执行耗时 |
| created_at | TIMESTAMP | NOT NULL | 调用时间（@PrePersist） |

`ToolInvocation` 实体字段与列一一对应；`ToolInvocationRepository.findBySessionId(String)`。

## 审计接口（oryxos-core → oryxos-storage 实现）

- `ToolInvocationAuditor.record(sessionId, toolName, inputJson, resultJson, success, errorMessage, durationMs)`（core，新增，本节交付物"表"的写入口）→ `JpaToolInvocationAuditor`（storage，自吞异常记 ERROR）。
- `LlmCallAuditor`（D1 移入 core）→ `JpaLlmCallAuditor`（storage，仅改 import）。

## 状态与生命周期

- `ProfileContext`：ThreadLocal\<Profile\>；set（process 入口）→ current（工具执行期间可读）→ clear（finally，必达，用 remove()）。
- Session 生命周期（本节视角）：外部构造 → run 期间累积 → 正常结束 `SessionManager.save`；异常路径不 save、不清空已累积消息。
