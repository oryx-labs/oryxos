# Contract: `oryxos chat` CLI + AgentService

用户交付面（US1）。Picocli 子命令 + AgentService 门面。

## CLI 契约

```text
oryxos chat [--profile <name>]
```

- `--profile`：Profile 名，缺省为 `default`。
- 启动一个同步 REPL：打印欢迎行 → 循环 { 读取一行输入 → 调用 AgentService → 打印最终回答 }。
- 退出：输入 `exit` / `quit` 或 EOF（Ctrl-D）→ 干净退出（exit code 0），无异常堆栈（US1-AS3）。
- 启动时若 Profile 缺失/非法（provider 名未知、凭证环境变量未设）→ 打印指向具体配置项的清晰
  错误并以非零码退出（FR-007 / US3-AS2）。

## AgentService 契约（oryxos-core 门面）

```java
public final class AgentService {
  AgentService(SessionManager sessions, ProfileLoader profiles, ReActLoop loop);

  /** 单次用户输入 → 最终回答。维护/复用该 sessionId 的会话。 */
  String chat(String sessionId, String profileName, String userInput);
}
```

## 行为契约

1. `chat` 解析/校验 Profile（首次），取或建该 `sessionId` 的 `Session`（`SessionManager`）。
2. 委托 `ReActLoop.run(session, profile, userInput)`，返回最终回答。
3. 同一进程内不同 `sessionId` 的会话上下文互不干扰（FR-011）。
4. 全同步，无异步（原则 VII）。

## 测试契约

- T1: 注入 fake `ReActLoop`，两轮输入 → 第二轮的历史包含第一轮（会话记忆，US1-AS2）。
- T2: 两个不同 sessionId 交替对话 → 各自历史隔离（FR-011）。
- T3: 未知 provider 的 Profile 启动 → 清晰错误、非零退出（US3-AS2）。
- 手动/集成：真实凭证下 `oryxos chat` 完成 ≥2 轮纯对话（US1，见 quickstart）。
