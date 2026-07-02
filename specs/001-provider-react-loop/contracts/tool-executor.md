# Contract: ToolExecutor & ToolRegistry (oryxos-core)

工具执行抽象。本特性**不交付真实工具**，只定义抽象 + 审计写入 + 沙箱接缝，供 ReActLoop 依赖，
后续工具特性填充实现（Clarify Q2 / R4）。

## 接口

```java
public interface ToolRegistry {
  Optional<OryxTool> find(String name);   // 本特性可返回空注册表
  List<OryxTool> all();                    // 供 PromptBuilder 生成工具 schema 列表
}

public final class ToolExecutor {
  ToolExecutor(ToolRegistry registry, ToolInvocationRepository audit /*, SandboxChecker checker */);

  /** 执行一次工具调用；无论成败都写 tool_invocations 审计。 */
  ToolResult execute(String sessionId, String toolName, JsonNode arguments);
}
```

## 行为契约

1. `execute` 从 `ToolRegistry` 查 `toolName`：
   - 未找到 → 返回 `ToolResult.error("unknown tool: <name>", retryable=false)`（不抛异常）。
   - 找到 → （预留：先过 `SandboxChecker` 白名单，原则 VI）→ 调 `OryxTool.execute(arguments)`。
2. 无论成功或失败，**同步写 `tool_invocations`**（toolName/inputJson/resultJson/success/
   errorMessage/durationMs/sessionId，原则 V）。
3. 不抛出到调用方；失败以 `ToolResult(success=false, retryable=…)` 返回，由 ReActLoop 回填。
4. `SandboxChecker` 的具体校验逻辑本特性只留接缝，实现于工具特性。

## 测试契约（fake / 空注册表）

- T1: 空注册表 + 未知工具名 → 返回 error 结果，且写入一条 `success=false` 审计。
- T2: 注入 fake `OryxTool` 返回 ok → 返回其 `ToolResult`，写入 `success=true` 审计。
- T3: fake `OryxTool` 抛异常 → 被捕获为 `ToolResult.error`，写入失败审计，不冒泡。
