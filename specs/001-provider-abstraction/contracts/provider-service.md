# Contract: ProviderService（第16节对下游暴露的唯一接口）

消费方：第 17 节 ReActLoop（本节内以测试为唯一调用方）。

## 方法签名（逐字保真，不得顺手改进）

```java
public ProviderResponse chat(String sessionId, Profile profile, ProviderRequest request)
```

> 课件口径 `chat(sessionId, Profile, Prompt)` 中的 "Prompt" 即此处 `ProviderRequest`（要发给模型的内容 + 可用工具列表）——不以 Spring AI 的 `Prompt` 类型出现在公共签名，避免框架类型泄漏到下游（research D8，停点待确认项）。

## 入参

| 参数 | 类型 | 约束 |
|---|---|---|
| sessionId | String | 非空；用于 llm_calls 关联 |
| profile | Profile | `profile.provider().name()` 决定路由；名字不在映射表 → 抛 `ProviderNotFoundException`（含名字） |
| request | ProviderRequest | `content`（发给模型的文本，非空）+ `availableTools`（`List<OryxTool>`，可空=不带工具） |

## 出参 ProviderResponse

| 字段 | 说明 |
|---|---|
| text | 模型文本回复（可能为空——当模型选择调工具时） |
| toolCalls | 模型提出的工具调用请求列表（name + argumentsJson），**原样透传、本模块零执行** |
| usage | token 用量（prompt/completion/total） |

## 行为契约

1. **路由**：按 `provider.name` 查显式映射表；命中即用、未命中抛异常，绝不降级到其他家（SC-001/002）。
2. **翻译不执行**：`availableTools` 经 ToolSchemaAdapter 翻译为 Spring AI 工具描述挂到请求；框架自动执行关闭；响应中的 tool call 原样进 `toolCalls`（SC-005）。
3. **审计**：返回前（成功）或抛出前（失败）恰好写一条 llm_calls；失败记录含可读 error_message；审计自身失败仅 ERROR 日志（D5）。
4. **异常**：底层调用异常原样上抛（RuntimeException），不重试、不熔断、不换家。

## 显式非契约（本节不承诺）

流式、多轮循环、工具执行、fallback、成本聚合。
