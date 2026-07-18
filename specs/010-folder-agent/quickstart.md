# Quickstart / 验收指南：插件化 Agent

前置：JDK 21、已构建。凭证走环境变量（`DEEPSEEK_API_KEY` 等），不落明文。本节无 `@Tag("integration")`；真模型/真定时/脚本真跑的人工项在 31 节。

## 自动化门禁（实现完成的定义）

```bash
# 全量门禁（含 P3C / SpotBugs / FindSecBugs / PMD）
mvn clean verify
# 期望：BUILD SUCCESS，全绿
```

## 只跑本节 harness（reactor 干净跑，避免 stale classpath）

```bash
mvn -pl oryxos-boot -am clean test -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='AgentLoaderTest,DeriveProfileTest,AgentScanRegisterTest,ProfileRegistryRuntimeTest,AgentSchedulerRegisterTest,ProgressiveDisclosureTest'
# 期望：6 个测试类全绿
```

## 关键回归点 ↔ 验收场景

| 验收点（spec SC/FR） | 怎么验（harness） |
|---|---|
| 扫 N 个目录 → 注册表 N 个、不产生别的（SC-002/FR-002） | `AgentScanRegisterTest`：临时目录放 N 个 `AGENT.md`，`AgentLoader.loadAll()` 后 `registry.all().size()==N` |
| frontmatter 正确派生、schedules 带进（SC-002/FR-004） | `DeriveProfileTest`：断言各字段映射、`profile.schedules()` == frontmatter 声明 |
| 正文进 prompt、子资源不预载（SC-005/FR-003,FR-004） | `ProgressiveDisclosureTest`：`ContextLoader.load` 结果含正文、不含 `skills/`/`REFERENCE.md`/脚本内容 |
| 改正文即时生效（SC-003/FR-003） | `ProgressiveDisclosureTest`：改盘上 AGENT.md 正文后再 `load`，结果反映新正文（无缓存） |
| 运行时/启动同一异常同一消息（SC-004/FR-006） | `ProfileRegistryRuntimeTest`：非法配置经运行时 `register` 与启动路径抛出的异常类型 + message 完全相等 |
| 定时留可注销句柄（FR-007） | `AgentSchedulerRegisterTest`：`registerProfile` 后 `scheduledTasks` 含该任务句柄 |
| 缺必填点名、坏 Agent 不阻断（FR-009） | `AgentLoaderTest`：缺 name/provider → `ProfileValidationException` 点名；一坏一好目录 → 好的仍登记 |

## 手动路径参照物（示例 Agent）

`my-agent/.oryxos/agents/daily-reconcile/`（第29节课件 §1.3–1.4 全文规格，由本节产出）：

```text
daily-reconcile/
├── AGENT.md              # frontmatter(profile) + 4 步正文
├── scripts/reconcile.py  # 确定性比对，输出差异 JSON
├── skills/report-format.md
└── REFERENCE.md
```

启动后（`OryxOsRuntime` 扫 `.oryxos/agents/`）它出现在已注册 Agent 列表；`oryxos profile list` 可见。脚本真跑 / 定时真触发的端到端联调在 31 节。

## 前序回归（跨节契约）

```bash
mvn -pl oryxos-core -am test
# 期望：17 节 ContextLoaderTest / PromptBuilderTest、25 节 AgentSchedulerTest、16 节 ProfileLoaderTest 随改造点同步后全绿
```
