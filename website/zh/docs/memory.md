# 记忆系统

OryxOS 为 Agent 提供三层记忆。每一层有不同的作用域、生命周期和存储后端。`MemoryService` 是统一的门面，`ReActLoop` 和 `PromptBuilder` 都通过它来访问记忆，不直接接触各层的实现。

## 三层记忆

| 层级 | 存储 | 作用域 | 核心阶段 |
| --- | --- | --- | --- |
| 会话记忆 | SQLite `sessions.messages_json` | 仅限当前对话 | 是 |
| 长期记忆 | `.oryxos/agents/<name>/MEMORY.md`（按 Agent 隔离） | 跨会话持久化，按 Agent 隔离 | 是 |
| 情景记忆 | 向量数据库（规划中） | 跨历史会话的语义检索 | 否 |

**会话记忆** 是对话历史——当前会话中积累的消息列表。以 JSON 格式序列化存入 SQLite。`PromptBuilder` 在每次构建 Prompt 时注入最近 `max_history_turns` 轮。它自动积累，Agent 不需要显式写入。

**长期记忆** 是一个只追加、带时间戳的 Markdown 文件，Agent 通过 `save_memory` 工具写入、通过 `recall_memory` 关键词检索。跨会话持久化，每次构建 Prompt 都会注入。Agent 用它记住用户偏好、项目事实，以及对话结束后值得保留的内容。

**情景记忆** 规划在扩展阶段实现。它会将历史对话建索引到向量数据库，在构建 Prompt 时检索语义相关的历史片段。

## 长期记忆按 Agent 隔离

长期记忆遵循「一个目录 = 一个 Agent」原则：每个 Agent 有自己的记忆文件 `.oryxos/agents/<name>/MEMORY.md`，与该 Agent 的 `AGENT.md`、`skills/` 同目录。无 Agent 上下文（非 Agent 触发的直接调用、单测）时，回退到全局 `.oryxos/memory/MEMORY.md`。Agent 名会做安全校验，任何非法段一律回退全局文件，防止目录穿越。

当前是哪个 Agent 由执行上下文决定——`save_memory` / `recall_memory` 工具路径通过 `ToolExecutionContext` 置入，读路径（`buildContext` / 按 Agent 读取）在 load 前后临时置入。

### 时间戳与两个区块

每条记录都写成一行**带时间戳**的内容：`- [yyyy-MM-dd HH:mm:ss] <内容>`。一个 `MEMORY.md` 文件按两个区块组织：

- `## 核心记忆`（**core**）——始终完整注入，从不截断
- `## 归档记忆`（**archival**）——运行足迹；量大时超限截断

`save_memory` 写入其中一个区块（`core` 或 `archival`）。recall 和截断只作用于**归档区**；核心区物理隔离，始终完整保留。

### 自动触发足迹

每次 Agent 被触发，OryxOS 都会自动向**该 Agent 的归档记忆**追加一行——把这次的用户消息和回复压成一条紧凑记录。这让每个 Agent 都有一份「自己干过什么」的可审计足迹，Agent 无需自己调用 `save_memory`。

## 长期记忆的操作

`LongTermMemoryStore`（默认后端：`MarkdownMemoryStore`）提供这些操作：

| 方法 | 行为 |
| --- | --- |
| `append(content, scope)` | 向核心区或归档区追加一条带时间戳的记录 |
| `load()` | 返回两个区块；归档区超限则截断，核心区完整返回 |
| `recallByKeyword(keyword)` | 返回归档区中包含该关键词的行 |
| 截断 | 归档区超过 4000 字符时，从头部截断，保留最近的内容 |

4000 字符的限制防止归档区占用过多 context window。截断时最旧的归档条目先删，优先保留近期内容。核心区永远不动。

这个接口在设计上为向量检索预留了升级路径。`recallByKeyword` 可以替换为语义检索实现，不需要改动 `MemoryService` 或任何调用方。

## 可插拔后端

长期记忆后端通过 `memory.backend` 配置项可插拔——换后端只换注入的 `LongTermMemoryStore`，`MemoryService` 门面签名与所有调用方保持不变。

| `memory.backend` | Store | 说明 |
| --- | --- | --- |
| `markdown`（默认） | `MarkdownMemoryStore` | 零依赖、人可读、git 可跟踪的 Markdown 文件 |
| `sqlite` | `SqliteMemoryStore` | 记忆按条存入 `memory_entries` 表；记忆量变大后的结构化升级，仍零外部依赖（复用既有 SQLite） |
| `mem0` | `Mem0MemoryStore` | 委托给外部 mem0 记忆服务 |

## save_memory 和 recall_memory 工具

Agent 通过两个内置工具与长期记忆交互，不直接写 `MEMORY.md`。

**`save_memory`** — 向当前 Agent 的 `MEMORY.md` 追加一段文本，写入 `core` 或 `archival` 区块。Agent 自己决定保存什么、写哪个区、何时保存。核心阶段没有自动摘要。

```text
工具: save_memory
输入: { "content": "用户偏好简洁的回复，不用列表。", "scope": "archival" }
效果: 向该 Agent 的 MEMORY.md 归档区追加带时间戳的条目
```

**`recall_memory`** — 按关键词检索归档区，返回匹配的行。在保存之前，Agent 可以先检索确认自己是否已经知道某件事，避免重复记录。

```text
工具: recall_memory
输入:  { "keyword": "偏好" }
输出: "- [2026-07-22 09:30:00] 用户偏好简洁的回复，不用列表。"
```

两个工具都经过 `ToolExecutor` 处理，和其他工具调用一样记录在 `tool_invocations` 里。

## 通过 REST 读取记忆

管理台通过以下端点读取某个 Agent 的长期记忆：

```text
GET /api/v1/agents/{name}/memory
```

响应（包裹在统一的 `{ code, message, data, timestamp }` 信封里）携带该 Agent 的 `MEMORY.md`，管理台把它渲染成**两张表**：核心记忆（core）和归档记忆（archival）。

## 记忆如何注入到 Prompt

`PromptBuilder` 在每次循环迭代时调用 `MemoryService.buildContext(session)`，将结果插入系统提示词的第二段，紧跟在 Agent 身份和 Bootstrap 文件之后：

```text
[1] 系统提示词（AGENT.md 正文 + bootstrap）
[2] MEMORY.md  ← 在这里注入：核心区全量 + 归档区截断至 4000 字符
[3] 对话历史（最近 N 轮）
[4] 可用工具（JSON Schema）
```

记忆块始终存在，即使 `MEMORY.md` 为空。

## MEMORY.md 与 USER.md 的区别

这两个文件看起来相似，但服务于相反的目的：

| | `MEMORY.md` | `USER.md` |
| --- | --- | --- |
| 谁来写 | Agent（通过 `save_memory`，外加自动触发足迹） | 用户（手动编写） |
| OryxOS 的行为 | 读 + 写 | 只读 |
| 内容 | Agent 积累的观察、学到的事实，以及运行历史 | 用户的初始偏好和背景信息 |
| 重置 | 从不（只追加；仅归档区过长时截断） | 从不（由用户自行管理） |
| 位置 | `.oryxos/agents/<name>/MEMORY.md`（按 Agent 隔离） | `.oryxos/USER.md` |

`USER.md` 属于 Bootstrap 组，在 Prompt 的第 [1] 段注入。`MEMORY.md` 在第 [2] 段注入。两者对 Agent 都可见，但只有 `MEMORY.md` 可以被 Agent 修改。

## 规划中的功能

- **情景记忆** — 将完整会话记录建索引到向量数据库；构建 Prompt 时检索最相关的 top-k 片段
- **向量化 recall** — 用基于 embedding 的语义检索替换 `recallByKeyword`，检索范围为 `MEMORY.md` 内容
- **记忆摘要** — 截断前自动压缩旧条目，用更少的字符保留更多信息
