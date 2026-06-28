# ReAct 循环

OryxOS 从零自实现 ReAct（Reason + Act）循环。整个循环约数十行 Java，让你完全掌控 Agent 的思考和行动过程——没有任何黑盒框架。

## 工作机制

```
用户消息
  → 追加到 Session 对话历史
  → PromptBuilder 组装 Prompt：
      [1] system prompt（Profile identity + Bootstrap + SKILL.md）← ContextLoader
      [2] 长期记忆（MEMORY.md 全文）                             ← MemoryService
      [3] 对话历史（最近 max_history_turns 轮）                  ← SessionManager
      [4] 可用 Tool 列表（Function Calling 格式）                ← ToolRegistry
  → ProviderService 调 LLM
  → [无 Tool 调用] → 返回最终响应
  → [有 Tool 调用] → ToolExecutor 执行 Tool
      → SandboxChecker 白名单校验
      → 执行（内置 Tool 在进程内 / MCP Tool 通过 JSON-RPC 转发）
      → 写 tool_invocations 审计表
      → 结果追加到对话历史
  → 回到组装 Prompt 继续循环（最多 max_iterations 次）
```

## 关键设计决策

**自实现，不委托给框架。** Spring AI 的 `ChatClient` 自动工具执行被显式禁用。ReAct Loop 和 `ToolExecutor` 完全掌控所有工具调度和执行。

**同步阻塞，不用响应式。** 循环全程同步阻塞，依靠 Java 21 Virtual Thread 处理并发。代码简洁，易于调试。

**审计默认开启。** 每次 LLM 调用写入 `llm_calls`，每次工具调用写入 `tool_invocations`——从第一天起。
