# Quickstart: 验证 Provider（第16节）

## 前置

- JDK 21、Maven；无需真实 API key（单测全 mock）。
- 冒烟（可选）需真实 key：`export DEEPSEEK_API_KEY=...`。

## 一键验证（机器判卷）

```bash
mvn clean verify          # 单测 + 全部静态检查；全绿 = harness 通过
```

预期：`ProfileLoaderTest`、`ProviderServiceTest`（含三个中文名关键回归）、`ToolSchemaAdapterTest`、`LlmCallRepositoryTest` 全绿；P3C/SpotBugs/FindSecBugs/PMD/Spotless/Checkstyle/OWASP 无阻断。

## 关键场景对照（对应 spec 验收）

| 场景 | 验证方式 |
|---|---|
| 双 provider 不串台（US1/SC-001） | `ProviderServiceTest.按名路由_两个provider不串台`（verify 另一家 never） |
| 错误名显式报错（SC-002） | `ProviderServiceTest`：未知名抛 `ProviderNotFoundException` 且信息含名字 |
| 失败也留痕（US2/SC-003） | `ProviderServiceTest.调用失败_审计必须留下success为false的记录` |
| 自动执行关闭（SC-005） | `ProviderServiceTest.带工具schema调用_请求里关闭了自动执行` |
| 坏 Profile 不阻断（SC-007） | `ProfileLoaderTest`：坏 YAML 跳过、其余可用 |
| 手工建表可存可读 | `LlmCallRepositoryTest`：执行 schema.sql 后写读 llm_calls |

## 冒烟（人工，可选）

```bash
DEEPSEEK_API_KEY=xxx mvn test -Dgroups=integration   # ProviderSmokeIT：真调一次、拿到响应、llm_calls +1 条 success=true
```

## 人工项（harness 覆盖不到，课件"五、做完怎么验"）

1. `mvn dependency:tree -pl oryxos-provider` 确认 `spring-ai-openai:1.0.0-M6` 解析成功；
2. 冒烟真跑过一次；
3. `grep -rn "sk-" --include='*.java' --include='*.yaml' oryxos-*/src` 结果为 0（无明文 key）。

契约与字段定义见 [contracts/provider-service.md](./contracts/provider-service.md) 与 [data-model.md](./data-model.md)。
