# Quickstart & Validation: Provider 抽象 + ReAct 循环

验证本特性端到端可用的最小步骤。实现细节见 [contracts/](./contracts/) 与 [data-model.md](./data-model.md)，
此处只给可运行的验收场景。

## 前置条件

- JDK 21（用 `/Users/oker/Library/Java/JavaVirtualMachines/ms-21.0.7` 跑 Maven，见项目记忆）。
- 至少一家 Provider 的凭证经环境变量注入，例如：
  ```bash
  export DEEPSEEK_API_KEY=***   # 由用户自行提供，不写入仓库
  ```
- 一个默认 Profile：`.oryxos/profiles/default.yaml`，`provider.api_key: ${DEEPSEEK_API_KEY}`。

## 构建

```bash
export JAVA_HOME=/Users/oker/Library/Java/JavaVirtualMachines/ms-21.0.7/Contents/Home
mvn -B clean verify        # 全部质量门禁需绿（Spotless/P3C/Checkstyle/SpotBugs）
```

## 场景 1 — 多轮对话（US1，需真实凭证）

```bash
oryxos chat --profile default
```

预期：
- 输入问候 → 得到自然语言回答，会话保持（US1-AS1）。
- 追问依赖上文的问题 → 回答体现对上文的理解（US1-AS2）。
- 输入 `exit` → 干净退出，无堆栈（US1-AS3）。

验收标准：新用户配置好凭证后 5 分钟内跑通一次多轮对话（SC-001）。

## 场景 2 — 工具调用调度机制（US2，测试替身，无需真实工具/凭证）

以自动化测试验证（本特性不交付真实工具）：

```bash
mvn -B test -pl oryxos-core -Dtest=ReActLoopTest
```

预期覆盖 [react-loop.md](./contracts/react-loop.md) 的 T1–T5：含 tool call 的 fake 响应触发
"执行→回填→继续"，工具恰好执行一次，多步整合，超限优雅收尾，失败回填不崩溃（SC-002 / SC-005）。

## 场景 3 — Provider 切换与配置校验（US3）

- 路由单元测试（无需两家真实凭证）：
  ```bash
  mvn -B test -pl oryxos-provider -Dtest=ProviderServiceTest
  ```
  验证两个 name 路由到各自 fake `ChatModel`（US3-AS1）、未知 name 抛清晰错误（US3-AS2）。
- 配置级：准备 `provider.name` 拼错的 Profile 启动 `oryxos chat` → 报错指向该配置项（FR-007）。

## 场景 4 — 审计落库（原则 V / SC-004）

一次对话或测试运行后，检查 SQLite：

```bash
sqlite3 .oryxos/oryxos.db "select count(*) from llm_calls;"
sqlite3 .oryxos/oryxos.db "select tool_name,success from tool_invocations;"
```

预期：模型调用次数与 `llm_calls` 行数一致；工具执行（测试中的 fake）在 `tool_invocations`
留有记录，成功与失败都写。

## 完成定义（Definition of Done）

- [ ] `mvn clean verify` 全绿。
- [ ] 场景 1 手动跑通（有凭证时）。
- [ ] 场景 2、3 的单元测试通过（测试替身，无需凭证）。
- [ ] 场景 4：审计记录条数与实际调用次数一致。
- [ ] 无真实工具被交付；工具调度仅经测试替身验证（符合 Clarify Q2）。
