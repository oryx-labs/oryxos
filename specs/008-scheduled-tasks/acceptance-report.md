# 第25节验收报告：定时任务模块——让 Agent 到点自己干活

**日期**: 2026-07-12 | **分支**: `class-25` | **任务**: 8/8 完成（tasks.md 全勾）

## 六项证据 DoD

### 1. `mvn clean verify` 全绿 ✅

含 Spotless / Checkstyle / P3C(PMD) / SpotBugs / FindSecBugs 全部门禁。`BUILD SUCCESS`，测试 205 个全过：

```text
oryxos-core 40（较基线 +6：AgentSchedulerTest）| oryxos-provider 14 | oryxos-storage 18
oryxos-memory 25 | oryxos-tool 96 | oryxos-web 12
```

过程中被门禁拦下并修复：
- **测试 2 处真 bug**：(a) `ReentrantLock` 同线程可重入——重叠跳过测试改为**跨线程占锁**（真实重叠场景），tryLock 才真失败；(b) `CronTrigger.equals` 只比 cron 不比时区——时区回归改用固定 `SimpleTriggerContext` 下的 `nextExecution` 行为断言（与配置时区一致、与别的时区不一致）。
- **FindSecBugs CRLF_INJECTION_LOGS ×4**：`registerAll`/`runOnce` 的 id/cron/zone 进日志——加 `@SuppressFBWarnings` 局部抑制（值来自运营方手写 Profile YAML、非请求输入）；oryxos-core 首次引入 `spotbugs-annotations`(provided，与 tool/web 同款)。

### 2. harness 测试类逐一对号 ✅

`AgentSchedulerTest`（6 个）覆盖课件四个坑，两个关键回归英文方法名 + `@DisplayName` 保留课件中文原文：

- `previousRunStillActive_currentTriggerSkipped` `@DisplayName("上一次还没跑完_本次触发直接跳过")`——**关键回归**：跨线程占锁后 `runOnce`，`verify(agentService, never()).process(...)`（坑二重叠跳过）。
- `taskThrows_notRethrown_lockReleased` `@DisplayName("任务抛异常_不外抛且锁必须被释放")`——**关键回归二进宫**：`process` 抛异常，`assertDoesNotThrow(runOnce)` 后再 `runOnce` 一次，`verify(times(2)).process(...)` 证明锁真放了（坑三失败隔离 + 不死锁）。
- `registerPassesCronAndZoneToTrigger` `@DisplayName("注册时CronTrigger带上配置的cron和时区")`——ArgumentCaptor 抓 Trigger，`getExpression()` 断言 cron + `nextExecution` 行为断言时区（坑四）。
- `sessionIdentityFixed_reusesSameSession` `@DisplayName("会话三元组固定_两次触发拿同一Session")`——`getOrCreate("scheduler","scheduler",profileName)` ×2 + 同一 Session（会话身份约定）。
- 另加：单条非法 cron 不拖垮其它注册（FR-007）、无规则空跑不报错。

### 3. 交付物存在性核对 ✅

- 代码（新增）：`AgentScheduler`（`registerAll` + `runOnce` + `lockFor` + `taskLocks` 表）——oryxos-core.agent
- 代码（复用不改）：`Profile.ScheduleConfig(id,cron,zone,message)` 嵌套 record + `Profile.schedules` 字段（第16节建全）
- 测试：`AgentSchedulerTest`
- 配置：Profile.schedules 字段第16节已建全（本节不改）
- 约定：会话身份固定 `("scheduler","scheduler",profileName)`（`SCHEDULER_CHANNEL`/`SCHEDULER_USER` 常量）；失败只 `log.error` 不外抛
- 装配：`OryxOsRuntime` + `ThreadPoolTaskScheduler`(daemon+initialize) + `AgentScheduler`(initMethod=registerAll)

### 4. 前序节回归 ✅

16~24 节全部测试全绿（provider 14 + storage 18 + memory 25 + tool 96 + web 12 + core 基线 34）。本模块只调既有 `AgentService.process` 入口、不改任何前序契约。含 class-25 检出时的 D1 provider 回退修复（`SpringAiProviderServiceImpl` 恢复）。

### 5. H4 六条全局不变量自查 ✅

| # | 不变量 | 结论 |
|---|---|---|
| ① | 涉外 IO 过 Sandbox | 本模块不做涉外 IO；调 `AgentService.process`，工具涉外 IO 在其内部经 Sandbox（24 节接线） |
| ② | 审计成败都落库 | 钟推走 `AgentService.process` 内部既有 llm_calls/tool_invocations，失败也走同一条；本模块 grep 零审计逻辑 |
| ③ | 无明文 key | grep 零命中 |
| ④ | session_id 只在 SessionManager 拼 | 本模块只传三元组给 `getOrCreate`，不构造 session_id（grep 仅命中注释） |
| ⑤ | 无 Reactor/CompletableFuture/自建线程池 | 用框架 `TaskScheduler`（§8.5 指定），`runOnce` 同步阻塞；grep 零命中 |
| ⑥ | 无 Spring AI 自动执行 | 本模块不触碰 LLM 调用路径 |

### 6. 剩余人工项（harness 判不了，等你过）

1. **真实到点触发一次**：给某 Profile 的 `schedules` 配一条"每分钟"（cron `0 * * * * *` + zone `Asia/Shanghai` + 一句话），`oryxos serve` 起服务，到点看 Agent 自动发起对话、`llm_calls`/`tool_invocations` 有账（cron 链路只能真等一次）。
2. **配置驱动体感**：改 cron 表达式，不重新编译、重启（或重新加载）后按新时间跑。
3. **端到端预演**：完整走"到点自动触发 → 跑完 ReAct 循环 → 留审计"，为 31 节两个定时 Demo 踩地基。

## 备注

- **两处 faithful 修正（测试）**：课件 harness 的重叠跳过示例用同线程 `lock.lock()`——`ReentrantLock` 可重入会让同线程 tryLock 成功，故改跨线程占锁（真实重叠是调度线程池跨线程）；时区回归因 `CronTrigger.equals` 不比时区，改 `nextExecution` 行为断言。两者都比课件示例更严谨、断言逻辑等价守点。
- **AgentScheduler 依赖 `TaskScheduler` 接口**（而非具体 `ThreadPoolTaskScheduler`）：DIP + 可测；装配处仍造 `ThreadPoolTaskScheduler`(daemon) 传入。
- **daemon 线程**：`ThreadPoolTaskScheduler.setDaemon(true)` 保证 chat 一次性命令跑完进程正常退出（Edge Case）。
- `ScheduleConfig`/`Profile.schedules` 第16节预交付、本节零改动（类比 24 节 sandbox 预交付）。
- oryxos-core 新增 `spotbugs-annotations`(provided)——build-only 静态分析注解，与 tool/web 同款，无运行时footprint。
- 未 commit/push——同步时机由你决定。
