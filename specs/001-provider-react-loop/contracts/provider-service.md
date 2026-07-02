# Contract: ProviderService (oryxos-provider)

统一 LLM 调用入口 + Provider 显式映射（宪法原则 II、III）。

## 接口

```java
public final class ProviderService {
  /** 显式映射：provider name -> ChatModel。构造时按已配置凭证装配。 */
  ProviderService(Map<String, ChatModel> providerMap, LlmCallRepository audit);

  /** 用 profile 指定的 provider/model 调用 LLM，返回原始 ChatResponse（含可能的 tool call）。 */
  ChatResponse call(Session session, Profile profile, Prompt prompt);

  /** 已注册的 provider 名集合（供启动校验/`oryxos provider list`）。 */
  Set<String> availableProviders();
}
```

## 行为契约

1. 按 `profile.provider.name` 从 `providerMap` **查表路由**到对应 `ChatModel`；MUST NOT 靠
   Bean 类型扫描（原则 III）。
2. 映射中不存在该 name → 抛出清晰错误，指明"未知/未配置的 provider: <name>"（FR-007）。
3. 仅调用 `chatModel.call(prompt)` 做协议转换；**不启用任何自动工具执行**（原则 II）。工具
   schema 通过 Prompt 的 options 声明，tool call 由调用方（ReActLoop）解析。
4. 每次调用后**同步写 `llm_calls`**（provider/model/tokens/durationMs/sessionId，原则 V）。
5. 凭证来自 Profile 的 `${ENV_VAR}` 解析值，MUST NOT 出现在日志/异常消息里（原则 VI）。

## 测试契约

- T1: 两个 name 指向两个 fake `ChatModel` → `call` 分别路由到正确的那个（US3 路由正确性）。
- T2: 未知 provider name → 抛出含该 name 的清晰异常。
- T3: 一次成功调用后 → `LlmCallRepository` 收到一条含 token/耗时的记录。
