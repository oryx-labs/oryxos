# 记忆系统

OryxOS 提供三层记忆系统。每一层有不同的用途和生命周期。

## 三层架构

| 层级 | 存储 | 生命周期 | 管理者 |
|------|------|---------|-------|
| **会话记忆** | SQLite `sessions.messages_json` | 当前会话 | SessionManager |
| **长期记忆** | `.oryxos/memory/MEMORY.md` | 跨会话持久化 | MemoryService |
| **情景记忆** | — | 路线图 | — |

## 长期记忆工具

Agent 通过 `save_memory` 工具写入长期记忆：

```bash
Tool: save_memory
Input: {"content": "用户偏好深色模式，使用 macOS"}
```

Agent 通过 `recall_memory` 工具检索长期记忆：

```bash
Tool: recall_memory
Input: {"query": "用户偏好"}
Output: "用户偏好深色模式，使用 macOS"
```

## 自动注入

长期记忆（`MEMORY.md`）由 `PromptBuilder` 自动注入到每个 system prompt 中。Agent 总是能访问它已经学到的内容——无需主动触发 recall。

## MEMORY.md 与 USER.md 的区别

- `USER.md`：用户手写的初始设定，OryxOS 只读不写，包含初始偏好和上下文。
- `MEMORY.md`：Agent 通过 `save_memory` 写入的成长记录，记录 Agent 随时间学到的内容。
