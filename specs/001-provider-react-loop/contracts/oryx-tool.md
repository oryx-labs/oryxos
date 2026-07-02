# Contract: OryxTool (oryxos-core, 既有)

所有工具的统一抽象（既有接口，本特性不新增实现，仅确认契约供 ToolExecutor/Registry 依赖）。

## 接口

```java
public interface OryxTool {
  String getName();
  String getDescription();
  JsonSchema getInputSchema();          // 供 PromptBuilder 生成 function-calling schema
  ToolResult execute(JsonNode input);
}

public record ToolResult(boolean success, String content,
                         String errorMessage, boolean retryable) { … }
```

## 契约要点

- `getInputSchema()` 产出的 schema 用于向模型声明工具（原则 II：schema 生成走 Spring AI `@Tool`
  或等价机制）。
- `execute` MUST NOT 自行写审计（审计由 `ToolExecutor` 统一负责，避免重复/遗漏）。
- 本特性范围内 `ToolRegistry` 通常为空，故无 `OryxTool` 实现被调用；真实实现见工具特性。
