# 第16节验收报告：Provider——对接大模型的统一入口

**日期**: 2026-07-09 | **分支**: `class-16` | **任务**: 20/20 完成（tasks.md 全勾）

## 六项证据 DoD

### 1. `mvn clean verify` 全绿 ✅

含 Spotless / P3C(PMD) / Checkstyle / SpotBugs / FindSecBugs / OWASP 全部门禁。`BUILD SUCCESS`，测试 23 个全过：

```text
ProfileLoaderTest        6/6   (oryxos-core)
ProviderServiceTest      7/7   (oryxos-provider)
ProvidersPropertiesTest  4/4   (oryxos-provider)
ToolSchemaAdapterTest    3/3   (oryxos-provider)
LlmCallRepositoryTest    3/3   (oryxos-storage，@DataJpaTest + 手工 schema.sql + SQLite)
ProviderSmokeIT          1（@Tag("integration")，surefire 默认排除）
```

过程中被门禁拦下并修复：SpotBugs 6 项（CRLF 日志净化改为 `replace(char,char)` 形态、`getFileName()` 判空）——安全规则未做任何排除。

### 2. harness 测试类逐一对号 ✅

课件映射表五个测试类全部存在且非空；三个中文名关键回归**原样落地**：
`按名路由_两个provider不串台`（verify 另一家 never()）、`调用失败_审计必须留下success为false的记录`（先落账再上抛）、`带工具schema调用_请求里关闭了自动执行`（ArgumentCaptor 断言 `proxyToolCalls=true` 且 schema 已挂载）。

### 3. 交付物存在性核对 ✅

- 代码：`Profile`/`ProfileLoader`/`ProfileRegistry`/`ProfileValidationException`（core）；`ProviderService`（签名逐字 `chat(sessionId, Profile, ProviderRequest)`）/`ToolSchemaAdapter`/`LlmCallAuditor`/`ProvidersProperties`/`ProviderChatModelFactory`/值对象四件+异常（provider）；`LlmCall`/`LlmCallRepository`/`JpaLlmCallAuditor`（storage）
- 配置：`oryxos.providers` 全局层（Properties + 启动校验点名报错）
- 表：`llm_calls` 手工 `schema.sql`（含 `success`/`error_message` 列，测试建表走此脚本、`ddl-auto=none`）
- 停点确认过的清单外项：`OryxTool.getInputSchema()`（对齐 TechSol §6.1）、`ProviderRequest` 等值对象（D8）

### 4. 前序节回归 ✅

16 节为首个代码节，无前序；全仓 `mvn clean verify` 即全部回归证据。

### 5. H4 六条全局不变量自查 ✅

| # | 不变量 | 结论 |
|---|---|---|
| ① | 涉外 IO 过 Sandbox | 本节无工具执行调用点（Provider 只翻译不执行）；真实出网仅冒烟 IT，默认排除 |
| ② | LLM 调用成败都落 llm_calls | 成功/失败路径各有测试钉死；失败先落账再上抛 |
| ③ | 无明文 key | grep `sk-` 模式零命中；凭证 `${ENV}` 占位 + 启动校验点名 |
| ④ | session_id 只在 SessionManager 拼 | 本节只透传 sessionId 字符串，不生成不拼接 |
| ⑤ | 无异步编程模型 | grep CompletableFuture/Reactor/Mono/Flux 零命中 |
| ⑥ | 无 Spring AI 自动执行 | 无 ChatClient；`proxyToolCalls(TRUE)` 硬编码于唯一调用路径；适配器 call() 抛异常为第二道保险 |

### 6. 剩余人工项（harness 判不了，等你过）

1. **依赖树目检**：`mvn dependency:tree -pl oryxos-provider | grep spring-ai-openai` 确认 `1.0.0-M6`（编译已实证可解析，目检一眼即可）；
2. **真实冒烟**：`DEEPSEEK_API_KEY=xxx mvn test -pl oryxos-provider -Dgroups=integration -DexcludedGroups=` 跑 `ProviderSmokeIT`，拿到真响应；
3. **明文复查**：按你实际用的 key 前缀再 grep 一遍（脚本查的是 `sk-` 通用模式）。

## 备注

- M6 API 全部经 javap 实核后才落码（`OpenAiApi(baseUrl,apiKey)`、`proxyToolCalls`、`getText()`——期间抓到一次臆造 `getContent()` 被编译门禁拦下并纠正），过程记录在 research.md D1/D2。
- 未 commit/push——同步时机由你决定（`package.sh` 或手动）。
