# ReAct Loop

OryxOS 从零实现了 ReAct（Reason + Act）算法——`ReActLoop.java` 里数十行 Java 代码，没有框架委托，没有黑盒 Agent 抽象。每次迭代都是透明的、可审计的。

![ReAct Loop](/images/react-loop.svg)

## 循环如何运转

七个步骤，最多重复 `max_iterations` 次（默认 10 次）：

1. **接收** — 用户消息通过某个渠道到达（CLI、HTTP 等）
2. **追加** — 消息追加到 SQLite 里的 `Session` 对话历史
3. **构建 Prompt** — `PromptBuilder` 组装四段式 Prompt（见下文）
4. **调用 LLM** — `ProviderService` 将 Prompt 发送给配置的 Provider
5. **检查响应** — 判断模型返回的是工具调用还是最终答案
6. **无工具调用** → 返回响应给调用方，循环结束
7. **有工具调用** → `ToolExecutor` 执行工具，结果追加到历史，回到第 3 步

```
用户消息
  → 追加到 Session 历史                               [SessionManager → SQLite]
  → PromptBuilder 组装 Prompt
  → ProviderService 调用 LLM                          [写 llm_calls 表]
  → 无工具调用  → 返回最终响应
  → 有工具调用  → ToolExecutor
                    → SandboxChecker 白名单校验
                    → 执行（进程内 或 JSON-RPC 转发到 MCP server）
                    → 写 tool_invocations 表
                    → 结果追加到历史
  → 继续循环（默认最多 10 次）
```

## Prompt 里注入了什么

`PromptBuilder` 按以下顺序从四个来源组装 Prompt：

| # | 部分 | 来源 | 负责方 |
| --- | --- | --- | --- |
| 1 | 系统提示词 — Profile 身份 + Bootstrap 文件（`AGENTS.md`、`SOUL.md`、`USER.md`）+ `SKILL.md` | 文件系统 | `ContextLoader` |
| 2 | 长期记忆 — `MEMORY.md` 全文（截断至 4000 字符） | `.oryxos/memory/MEMORY.md` | `MemoryService` |
| 3 | 对话历史 — 最近 `max_history_turns` 轮 | SQLite `sessions.messages_json` | `SessionManager` |
| 4 | 可用工具 — Profile 暴露的每个工具的 JSON Schema | `ToolRegistry` | `ToolRegistry` |

Prompt 在每次迭代时重新构建。如果工具执行结果改变了模型下一步应该做的事，重新构建的 Prompt 会把这个变化带入。

## ToolExecutor

`ToolExecutor` 负责从 LLM 决定调用工具到结果加入对话历史之间发生的一切：

1. **解析** — 从模型响应中提取工具名称和参数
2. **查找** — 在 `ToolRegistry` 中找到对应的 `OryxTool` 实现
3. **校验** — `SandboxChecker` 对照配置的白名单验证调用
4. **执行** — 内置工具在进程内执行；MCP 工具通过 JSON-RPC 转发
5. **审计** — 向 `tool_invocations` 写入一条记录（成功与否、耗时、输入、结果）
6. **返回** — `ToolResult` 序列化后作为 assistant/tool 消息追加到对话历史

如果工具执行失败且 `ToolResult.retryable` 为 `true`，错误信息仍会追加到历史，让模型在下次迭代中尝试别的方案。

## 关键约束

**`max_iterations`** — 在 Profile YAML 里按 Profile 配置（默认 `10`），防止无限循环。达到上限时循环停止，返回最后一次 LLM 响应。

```yaml
settings:
  max_iterations: 10
  max_history_turns: 20
```

**上下文窗口管理** — 长期记忆截断至 4000 字符（`LongTermMemory.truncateIfNeeded()`），保留最近的内容。对话历史上限为 `max_history_turns` 轮。两种截断都不是静默发生的：PromptBuilder 会记录实际注入的长度。

**同步执行** — 整个循环是同步阻塞的。并发由 HTTP/渠道层的 Java 21 虚拟线程处理。循环内部不用 Reactor、不用 `CompletableFuture`、不用回调。

**Spring AI 工具执行已禁用** — `ChatClient` 的自动工具执行被显式关闭。如果启用，工具会执行两次——Spring AI 执行一次，`ToolExecutor` 再执行一次。`ProviderService` 直接调用 `chatModel.call(prompt)` 并将原始 `ChatResponse` 交给循环处理。

```java
// 错误 — Spring AI 会自动执行工具
chatClient.prompt(prompt).tools(tools).call().content();

// 正确 — 原始调用，循环自己检查并执行工具调用
ChatResponse response = chatModel.call(new Prompt(messages, options));
```

## 核心阶段不包含的内容

以下功能规划在扩展阶段实现，核心循环里没有：

- **并行工具调用** — 单次迭代中同时调用多个工具
- **流式响应** — SSE 或 WebSocket 流式传输部分 token
- **带退避的循环级重试** — 目前 LLM 调用失败会直接把错误抛给调用方
- **分支 / 子 Agent 委托** — 核心阶段只有单 Agent 线性循环
