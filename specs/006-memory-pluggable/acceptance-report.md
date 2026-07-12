# 第22节验收报告：Memory——让 Agent 记得住事的可插拔记忆层

**日期**: 2026-07-12 | **分支**: `class-22` | **任务**: 24/24 完成（tasks.md 全勾）

## 六项证据 DoD

### 1. `mvn clean verify` 全绿 ✅

含 Spotless / P3C(PMD) / Checkstyle / SpotBugs / FindSecBugs 全部门禁。`BUILD SUCCESS`，测试 181 个全过：

```text
oryxos-core 34 | oryxos-provider 14 | oryxos-storage 18 | oryxos-tool 76
oryxos-memory 25（Contract 12 + Markdown 3 + Mem0 3 + MemoryServiceImpl 2 + MemoryTools 5）
```

过程中被门禁拦下并修复：memory pom 缺 spring-ai-core（@Tool）、DefaultMemoryService→MemoryServiceImpl（P3C ServiceShouldEndWithImpl）、getParent 判空、EI_EXPOSE_REP2 局部抑制、@MethodSource 需 PER_CLASS。

### 2. harness 测试类逐一对号 ✅

`MemoryStoreContractTest` **参数化对三档统一跑**（markdown / sqlite / mem0替身，4 断言 × 3 = 12）；两个中文名关键回归原样落地：
`truncationKeepsCoreIntact` `@DisplayName("截断只裁归档区_核心记忆一字不能少")`、`writeIsImmediatelyReadable_noCache` `@DisplayName("写入后立刻可读_不允许有缓存")`——断言照课件逐条保真，三档全过。各档专属（Markdown 字符串截断/Mem0 mock REST）+ MemoryToolsTest + MemoryServiceImplTest 齐。

### 3. 交付物存在性核对 ✅

- 代码：`MemoryService`/`MemoryScope`（core，D1 上移）、`LongTermMemoryStore` + `MemoryServiceImpl` + 三档 store（`MarkdownMemoryStore`/`SqliteMemoryStore`/`Mem0MemoryStore`）+ `InMemoryMemoryStore`(替身) + `MemoryTools`（memory）、`MemoryEntry`/`MemoryEntryRepository`（storage）
- 表：`memory_entries` 手工 schema.sql（scope/content/created_at + idx_memory_scope）
- 配置：`memory.backend`（markdown 默认 / sqlite / mem0）+ mem0 地址凭证 `${MEM0_BASE_URL}` 占位
- 集成点：PromptBuilder D2 构造注入 memoryService（buildContext 注入长期记忆）+ Runtime 按配置装配
- 停点确认过的项：D1 接口上移、D2 PromptBuilder 扩展、SqliteStore 持久化件落 storage、memory_entries/memory.backend/InMemoryMemoryStore、Mem0 端点"以部署版本为准"

### 4. 前序节回归 ✅

16~20 节 139 个测试全绿（上表 34+14+18+76，含 PromptBuilder D2 扩展非破坏——17 节 PromptBuilderTest 6 个断言零改）。

### 5. H4 六条全局不变量自查 ✅

| # | 不变量 | 结论 |
|---|---|---|
| ① | 涉外 IO 过 Sandbox | 记忆读写本地档不涉外；Mem0 档是配置的外部服务（非工具涉外 IO）；save/recall 工具经 ToolExecutor（沙箱位在工具执行链，24 节接线） |
| ② | 审计成败都落库 | save_memory/recall_memory 经 20 节 ToolExecutor 统一路径落 tool_invocations |
| ③ | 无明文 key | grep 零命中；Mem0 地址凭证 `${MEM0_BASE_URL}` 占位 |
| ④ | session_id 只在 SessionManager 拼 | 本节不触碰 |
| ⑤ | 无异步编程模型 | 文件/JPA/RestClient 同步；grep 零命中 |
| ⑥ | 无 Spring AI 自动执行 | MemoryTools 走 @Tool 注解管道（schema 生成）；无 ChatClient |

依赖方向 grep：core 主代码不引 io.oryxos.memory 实现包（只 core.memory 接口被 memory 实现）——D1 解环有效。

### 6. 剩余人工项（harness 判不了，等你过）

1. **三档切换同体感**：`memory.backend` 依次设 markdown / sqlite，各跑一次 chat，save_memory 写入、下一轮 buildContext 带上——同体感、底层已换。
2. **Mem0 档真连一次**：部署自托管 Mem0，`memory.backend: mem0` + `${MEM0_BASE_URL}`，验证 save 进 Mem0、recall 语义召回（依赖真 server，测不了；端点以部署版本为准，如对不上按实际 REST 微调 Mem0MemoryStore）。
3. **真模型跨会话**：对话里说一句值得记的，Agent 主动调 save_memory；开新会话核心记忆在场。
4. **MEMORY.md 可写、USER.md 只读**（code review 确认无写 USER.md 路径）。

## 备注

- 门面 buildContext 返回长期记忆（核心+归档截断），会话历史由 PromptBuilder 独立段负责，两者一起注入——课件第四部分守点已同步为此口径（`MemoryServiceImplTest`）。
- TechnicalSolution.md §5 已同步更新（可插拔后端 + 三档，消除课件冲突）。
- 未 commit/push——同步时机由你决定。
