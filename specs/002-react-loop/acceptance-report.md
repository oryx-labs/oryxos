# 第17节验收报告：ReAct 循环——Agent 的大脑

**日期**: 2026-07-10 | **分支**: `class-17` | **任务**: 28/28 完成（tasks.md 全勾）

## 六项证据 DoD

### 1. `mvn clean verify` 全绿 ✅

含 Spotless / P3C(PMD) / Checkstyle / SpotBugs / FindSecBugs / OWASP 全部门禁，10 模块 Reactor 全 SUCCESS。本节测试 22 个 + 16 节回归 23 个全过：

```text
ReActLoopTest                 8/8   (oryxos-core，全 mock 不碰网络)
PromptBuilderTest             6/6   (oryxos-core，Clock.fixed 钉日期时间行)
ToolExecutorTest              5/5   (oryxos-core)
AgentServiceTest              5/5   (oryxos-core)
ContextLoaderTest             4/4   (oryxos-core，@TempDir + ListAppender)
ToolInvocationRepositoryTest  3/3   (oryxos-storage，@DataJpaTest + 手工 schema.sql + SQLite)
—— 16 节回归：ProfileLoaderTest 6 + ProviderServiceTest 7 + ProvidersPropertiesTest 4
   + ToolSchemaAdapterTest 3 + LlmCallRepositoryTest 3 = 23 全绿，断言零改动
```

过程中被门禁拦下并修复：Spotless 格式（apply 后过）；P3C `ServiceOrDaoClassShouldEndWithImpl` → 实现类定名 **`SpringAiProviderServiceImpl`**（停点方案名 SpringAiProviderService + 规约 Impl 后缀；修实现不改规则）。

### 2. harness 测试类逐一对号 ✅

课件映射表五个测试类全部存在且非空；**测试方法名英文 + `@DisplayName` 保课件中文原名（本节起新规）**。课件写出代码的两个关键回归断言逐条保真：

| @DisplayName（课件原文） | 落地方法 | 守点 |
|---|---|---|
| 模型一直要调工具_转满最大轮数强制停 | `modelKeepsRequestingTools_forceStopAtMaxIterations` | `verify(providerService, times(10))` 恰好 10 轮一轮不多 + `reply.contains("达到最大轮数")` |
| 处理中抛异常_ProfileContext也必须被清掉 | `processThrowsException_profileContextMustBeCleared` | `assertThrows` 后 `assertNull(ProfileContext.current())`——ThreadLocal 泄漏只在并发复用时串号，显式钉死 |

### 3. 交付物存在性核对 ✅

- 代码（oryxos-core）：`ReActLoop`（`run(Session, String, Profile)` 签名逐字，强制收尾字面量"达到最大轮数，已停止"）/`PromptBuilder`（四段拼接 + 日期时间行 + 截断）/`ToolExecutor`（唯一执行路径，沙箱检查位注释注明 24 节接线）/`AgentService`（`process(Session, String)`，set→try→finally clear）/`ProfileContext`（remove() 防泄漏）/`ContextLoader`（无缓存、Skill 缺失报错、Bootstrap 缺失 WARN）
- 存储（oryxos-storage）：`ToolInvocation` 实体 + `ToolInvocationRepository` + `JpaToolInvocationAuditor`（自吞异常记 ERROR）
- 表：`tool_invocations` 手工 `schema.sql`（success BOOLEAN NOT NULL / error_message / idx_tool_invocations_session）
- 约定：最大轮数默认 10、截断默认 20（16 节 Settings 默认值，测试断言按 Profile 配置生效）、prompt 末尾日期时间行
- 停点确认过的清单外项：前向最小 `Session`/`Message`/`SessionManager`（TechSol §10 出处，18 节补全）、`ToolInvocationAuditor` 接口、`ToolInvocationRepositoryTest`

### 4. 前序节回归绿 + D1 契约上移 ✅（本节最大架构动作，已获停点确认）

**动因**：落位表 `ReActLoop`→core 要调 provider 的 `ProviderService`，而 provider→core 依赖已存在 → Maven 循环依赖无法编译。
**做法**：`ProviderService` 抽为 core 接口（`chat(sessionId, Profile, ProviderRequest)` 签名逐字不变）+ `ProviderRequest`/`ProviderResponse`/`ToolCallRequest`/`Usage`/`LlmCallAuditor` 原样移入 `io.oryxos.core.provider`；provider 实现类 `SpringAiProviderServiceImpl`（实现体零改动）；**storage pom 移除对 provider 的依赖**——16 节验收报告 flag 的反向依赖就此解决。
**证据**：16 节 23 个测试断言零改动全绿；`grep "io.oryxos.provider" oryxos-core/src/main` 零命中。治理依据已入宪法 v1.1.0（模块结构可按需演进：跨模块契约放 core、依赖倒置、禁循环依赖）。

### 5. H4 六条全局不变量自查 ✅

| # | 不变量 | 结论 |
|---|---|---|
| ① | 涉外 IO 过 Sandbox | ToolExecutor 执行前留检查位注释（24 节 SandboxChecker 接线）；本节工具全为测试替身，无真实出网 |
| ② | 审计双表成败都写 | tool_invocations：成功/失败/未注册/坏 JSON 四路径测试钉死；llm_calls 复用 16 节 |
| ③ | 无明文 key | grep `sk-`/`api_key=明文` 模式零命中 |
| ④ | session_id 只在 SessionManager 拼 | 本节 Session 构造收现成 id、循环只透传；grep 无拼接 |
| ⑤ | 无异步编程模型 | grep CompletableFuture/Reactor/Mono/Flux/new Thread/ExecutorService 零命中，循环同步阻塞 |
| ⑥ | 无 Spring AI 自动执行 | core 主代码零 Spring AI import；`proxyToolCalls(TRUE)` 仍在唯一调用路径（SpringAiProviderServiceImpl） |

### 6. 剩余人工项（harness 判不了，等你过）

1. **Demo 一对话版真模型跑通**：需 18 节 `oryxos chat` 就位后端到端验证"查天气→穿搭建议"（本节 ReActLoopTest 的替身场景已代跑同一流转）；
2. **Code review 确认循环自实现**：`ReActLoop.run` 为手写 for 循环、无框架 Agent 封装（[ReActLoop.java](../../oryxos-core/src/main/java/io/oryxos/core/agent/ReActLoop.java) 约 30 行，一眼可过）。

## 备注

- Clarify 两条默认已在实现中生效并有测试：①"一轮 = 一条 user 消息及其后全部消息"（截断以轮为界不撕裂，PromptBuilderTest 专测）；②异常路径不持久化 Session（AgentServiceTest verify never）。
- 宪法修订 v1.0.0 → **v1.1.0**（模块结构可按需演进条款），CLAUDE.md 模块章节同步。
- 未 commit / push——同步时机由你决定（`package.sh` 或手动）。
