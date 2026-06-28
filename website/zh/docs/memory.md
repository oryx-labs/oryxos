# 记忆系统

OryxOS 为 Agent 提供三层记忆。每一层有不同的作用域、生命周期和存储后端。`MemoryService` 是统一的门面，`ReActLoop` 和 `PromptBuilder` 都通过它来访问记忆，不直接接触各层的实现。

## 三层记忆

| 层级 | 存储 | 作用域 | 核心阶段 |
| --- | --- | --- | --- |
| 会话记忆 | SQLite `sessions.messages_json` | 仅限当前对话 | 是 |
| 长期记忆 | `.oryxos/memory/MEMORY.md` | 跨会话持久化，按工作区隔离 | 是 |
| 情景记忆 | 向量数据库（规划中） | 跨历史会话的语义检索 | 否 |

**会话记忆** 是对话历史——当前会话中积累的消息列表。以 JSON 格式序列化存入 SQLite。`PromptBuilder` 在每次构建 Prompt 时注入最近 `max_history_turns` 轮。它自动积累，Agent 不需要显式写入。

**长期记忆** 是一个只追加的 Markdown 文件，Agent 通过 `save_memory` 工具写入、通过 `recall_memory` 关键词检索。跨会话持久化，每次构建 Prompt 都会注入。Agent 用它记住用户偏好、项目事实，以及对话结束后值得保留的内容。

**情景记忆** 规划在扩展阶段实现。它会将历史对话建索引到向量数据库，在构建 Prompt 时检索语义相关的历史片段。

## 长期记忆（MEMORY.md）

`LongTermMemory` 通过四个操作管理 `.oryxos/memory/MEMORY.md`：

| 方法 | 行为 |
| --- | --- |
| `append(content)` | 向文件追加一条带时间戳的记录 |
| `load()` | 将文件全文读取为字符串 |
| `recallByKeyword(keyword)` | 返回包含该关键词的行（大小写不敏感） |
| `truncateIfNeeded()` | 文件超过 4000 字符时，从头部截断，保留最近的内容 |

4000 字符的限制防止 `MEMORY.md` 占用过多 context window。截断时最旧的条目先删，优先保留近期内容。

这个接口在设计上为向量检索预留了升级路径。`recallByKeyword` 可以替换为语义检索实现，不需要改动 `MemoryService` 或任何调用方。

## save_memory 和 recall_memory 工具

Agent 通过两个内置工具与长期记忆交互，不直接写 `MEMORY.md`。

**`save_memory`** — 向 `MEMORY.md` 追加一段文本。Agent 自己决定保存什么、何时保存。核心阶段没有自动摘要。

```text
工具: save_memory
输入: { "content": "用户偏好简洁的回复，不用列表。" }
效果: 向 MEMORY.md 追加带时间戳的条目
```

**`recall_memory`** — 按关键词检索 `MEMORY.md`，返回匹配的行。在保存之前，Agent 可以先检索确认自己是否已经知道某件事，避免重复记录。

```text
工具: recall_memory
输入:  { "keyword": "偏好" }
输出: "用户偏好简洁的回复，不用列表。"
```

两个工具都经过 `ToolExecutor` 处理，和其他工具调用一样记录在 `tool_invocations` 里。

## 记忆如何注入到 Prompt

`PromptBuilder` 在每次循环迭代时调用 `MemoryService.loadLongTermMemory()`，将结果插入系统提示词的第二段，紧跟在 Profile 身份和 Bootstrap 文件之后：

```text
[1] 系统提示词（身份 + bootstrap + skill）
[2] MEMORY.md 全文  ← 在这里注入，截断至 4000 字符
[3] 对话历史（最近 N 轮）
[4] 可用工具（JSON Schema）
```

记忆块始终存在，即使 `MEMORY.md` 为空。空文件产生空字符串，对 Prompt 没有影响。

## MEMORY.md 与 USER.md 的区别

这两个文件看起来相似，但服务于相反的目的：

| | `MEMORY.md` | `USER.md` |
| --- | --- | --- |
| 谁来写 | Agent（通过 `save_memory`） | 用户（手动编写） |
| OryxOS 的行为 | 读 + 写 | 只读 |
| 内容 | Agent 积累的观察和学到的事实 | 用户的初始偏好和背景信息 |
| 重置 | 从不（只追加，仅在过长时截断） | 从不（由用户自行管理） |
| 位置 | `.oryxos/memory/MEMORY.md` | `.oryxos/USER.md` |

`USER.md` 属于 Bootstrap 组，在 Prompt 的第 [1] 段注入。`MEMORY.md` 在第 [2] 段注入。两者对 Agent 都可见，但只有 `MEMORY.md` 可以被 Agent 修改。

## 规划中的功能

- **情景记忆** — 将完整会话记录建索引到向量数据库；构建 Prompt 时检索最相关的 top-k 片段
- **向量化 recall** — 用基于 embedding 的语义检索替换 `recallByKeyword`，检索范围为 `MEMORY.md` 内容
- **记忆摘要** — 截断前自动压缩旧条目，用更少的字符保留更多信息
- **按 Agent 隔离记忆** — 每个 Profile 独立的 `MEMORY.md`，而不是按工作区共享
