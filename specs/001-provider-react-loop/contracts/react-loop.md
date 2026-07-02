# Contract: ReActLoop (oryxos-core)

自实现的推理主循环（宪法原则 I）。不使用 Spring AI 的 Agent 抽象或 ChatClient 自动工具执行。

## 接口

```java
public final class ReActLoop {
  ReActLoop(ProviderService providers, PromptBuilder promptBuilder,
            ToolExecutor toolExecutor, ToolRegistry toolRegistry);

  /** 驱动一次用户输入的完整推理，返回最终自然语言回答。 */
  String run(Session session, Profile profile, String userInput);
}
```

## 行为契约

1. 追加 `USER` 消息到 `session`，进入循环，迭代计数从 0 开始。
2. 每轮：
   a. `PromptBuilder` 组装 Prompt = [system(identity+bootstrap) + 裁剪后历史 + 工具 schema]。
   b. 经 `ProviderService.call(profile, prompt)` 调模型（内部写 `llm_calls`）。
   c. 若响应**无 tool call** → 追加 `ASSISTANT` 最终回答，返回该文本（终止）。
   d. 若响应**有 tool call** → 对每个 call：`ToolExecutor.execute` 执行（内部写
      `tool_invocations`），把结果作为 `TOOL_RESULT` 追加，继续下一轮。
3. 每个 tool call 在本轮**恰好执行一次**（FR-004）。
4. 迭代到达 `profile.settings.maxIterations`（默认 10）仍未产出最终回答 → 终止并返回一条
   面向用户的说明字符串（FR-003 / SC-005），不抛异常、不无限循环。
5. 工具执行失败（`ToolResult.success=false`）→ 把失败信息作为 `TOOL_RESULT` 回填，继续循环，
   由模型决定重试或说明（FR-009 / R8）；**循环层不自动重试**。
6. 全程同步阻塞，无异步（原则 VII）。

## 测试契约（本特性用测试替身）

- 注入 fake `ProviderService`（返回预设 `ChatResponse` 序列）与 fake `ToolExecutor`。
- T1: 无 tool call 的响应 → 直接返回其文本。
- T2: 含 1 个 tool call 的响应，后续无 call → 工具执行 1 次、结果回填、返回最终文本。
- T3: 连续两轮 tool call → 依次执行、整合，最终只返回最后一条无 call 的回答。
- T4: 始终返回 tool call → 到 maxIterations 停止并返回说明串。
- T5: fake 工具返回 `success=false` → 失败回填、循环不崩溃、模型可继续。
