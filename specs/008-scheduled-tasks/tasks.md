# Tasks: 定时任务模块——让 Agent 到点自己干活

**Input**: specs/008-scheduled-tasks/（plan.md / research.md / data-model.md / contracts/scheduler.md / quickstart.md）

**Tests**: 课件"验收 harness"——`AgentSchedulerTest` 一个类覆盖四个坑；两个关键回归原样落地（英文方法名 + `@DisplayName` 保留课件中文）。

**Organization**: 依赖核对（ScheduleConfig 已建全）→ AgentScheduler + 契约测试（harness 先行）→ 装配 → Polish。

**既定决策（H0 已核实）**：`AgentScheduler` 落 `oryxos-core.agent`、构造注入 POJO（不用 `@Component/@PostConstruct`）；`Profile.ScheduleConfig`/`Profile.schedules` 第16节已建全、**MUST NOT 改**；失败审计复用 `AgentService.process` 既有链路、不新增；无新增第三方依赖（spring-context 传递自 spring-boot-starter）。

## Phase 1: Setup

- [ ] T001 基线 & 依赖核实：`mvn test -q` 确认 16~24 节全绿；`git grep -n "record ScheduleConfig" oryxos-core/src/main/java/io/oryxos/core/profile/Profile.java` 确认 `ScheduleConfig(id,cron,zone,message)` 与 `schedules` 字段已在；javap 复核 `CronTrigger(String, ZoneId)`（研究已核，二次确认无新增依赖）

## Phase 2: US1 到点自动发起 + US2 重叠跳过 + US3 失败隔离（P1，harness 先行——AgentScheduler 核心）

**Goal**: `AgentScheduler` 落地——启动注册（cron+时区）、到点触发交给 `AgentService`、重叠跳过、失败隔离+锁释放、会话三元组固定。三个 P1 故事共享同一实现类与同一测试类，合并为一个 harness 阶段。

**Independent Test**: `mvn test -pl oryxos-core -am -Dtest=AgentSchedulerTest` 全绿——含四坑四断言。

- [ ] T002 [US1] **harness 先行** `oryxos-core/src/test/java/io/oryxos/core/agent/AgentSchedulerTest.java`——mock `TaskScheduler`/`ProfileRegistry`/`AgentService`/`SessionManager`，四个测试：
  - `registerPassesCronAndZoneToTrigger` `@DisplayName("注册时CronTrigger带上配置的cron和时区")`——`ArgumentCaptor<Trigger>` 抓 `taskScheduler.schedule` 参数，断言是 `CronTrigger` 且其表达式/时区来自配置的 cron 与 zone（可用 CronTrigger.getExpression() 或反射/toString 校验 cron；zone 通过构造不同 zone 的两条断言区分）
  - `previousRunStillActive_currentTriggerSkipped` `@DisplayName("上一次还没跑完_本次触发直接跳过")`——**关键回归**：`scheduler.lockFor("task-1").lock()` 占锁后调 `runOnce(profile, sc("task-1"))`，`verify(agentService, never()).process(any(), any())`；finally 里 unlock
  - `taskThrows_notRethrown_lockReleased` `@DisplayName("任务抛异常_不外抛且锁必须被释放")`——**关键回归二进宫**：`when(agentService.process(any(),any())).thenThrow(new RuntimeException("boom"))`，`assertDoesNotThrow(() -> scheduler.runOnce(profile, sc("task-1")))`，再 `runOnce` 一次，`verify(agentService, times(2)).process(any(), any())`（证明锁真放了、没死锁）
  - `sessionIdentityFixed_reusesSameSession` `@DisplayName("会话三元组固定_两次触发拿同一Session")`——`sessionManager.getOrCreate` mock 返回同一 Session，两次 `runOnce` 断言入参恒为 `("scheduler","scheduler",profileName)` 且 `agentService.process` 收到同一 session 实例
- [ ] T003 [US1] `oryxos-core/src/main/java/io/oryxos/core/agent/AgentScheduler.java`——POJO，构造注入 `ThreadPoolTaskScheduler`+`ProfileRegistry`+`AgentService`+`SessionManager`；`ConcurrentMap<String,Lock> taskLocks`；常量 `SCHEDULER_CHANNEL="scheduler"`/`SCHEDULER_USER="scheduler"`：
  - `registerAll()`：遍历 `profileRegistry.all()`→`profile.schedules()`→每条 `sc`，`taskScheduler.schedule(() -> runOnce(profile, sc), new CronTrigger(sc.cron(), resolveZone(sc.zone())))`；单条 try/catch 记 WARN 跳过（FR-007）
  - `runOnce(Profile, ScheduleConfig)`：`lockFor(sc.id()).tryLock()` 拿不到→INFO 跳过 return；拿到→`try{ getOrCreate(SCHEDULER_CHANNEL,SCHEDULER_USER,profile.name()); agentService.process(session, sc.message()); } catch(Exception e){ log.error 不外抛 } finally{ unlock() }`
  - `lockFor(String)`：`taskLocks.computeIfAbsent(id, k -> new ReentrantLock())`；`private ZoneId resolveZone(String)`：blank→`ZoneId.systemDefault()` 否则 `ZoneId.of(zone)`
  - 语法禁区：for-each+lambda+传统 try/catch/finally，无增强 switch/record 模式；异常不吞（catch 落 log.error）
- [ ] T004 [US1] 阶段门禁：`mvn test -pl oryxos-core -am -Dtest=AgentSchedulerTest -Dsurefire.failIfNoSpecifiedTests=false` 全绿；红了修 `AgentScheduler`（不删断言、不放宽）

**Checkpoint**: 调度器四坑全绿，三个 P1 故事核心逻辑就位（MVP）。

## Phase 3: 装配

- [ ] T005 装配：`oryxos-cli/src/main/java/io/oryxos/cli/OryxOsRuntime.java`——新增 `@Bean ThreadPoolTaskScheduler taskScheduler()`（`setDaemon(true)` + `setPoolSize` 合理值 + `initialize()`，Edge Case 不阻塞 chat 退出）；新增 `@Bean(initMethod = "registerAll") AgentScheduler agentScheduler(ThreadPoolTaskScheduler, ProfileRegistry, AgentService, SessionManager)`（启动即注册）

## Phase 4: Polish

- [ ] T006 全仓硬门禁：`mvn clean verify` 全绿（含 Spotless/P3C/PMD/Checkstyle/SpotBugs/FindSecBugs），红了修实现（不放宽断言）
- [ ] T007 H4 六条全局不变量自查 + 交付物 ls/grep 核对（④ session_id 不在本模块拼、只传三元组给 getOrCreate；⑤ 无 Reactor/CompletableFuture/自建线程池——用框架 TaskScheduler，runOnce 同步；② 失败走 AgentService.process 既有审计、grep 确认本模块无新增审计逻辑；grep 无明文 key）
- [ ] T008 验收报告：`specs/008-scheduled-tasks/acceptance-report.md`（六项证据 + 人工项：真实到点触发看审计、改 cron 免重编译重启生效、端到端预演）

## Dependencies

- T001 → 全部。
- T002（harness）先于/伴随 T003；T003 → T004。
- T003 → T005（装配需实现类）。
- 主代码全绿 → T006 → T007 → T008。

## Parallel Opportunities

- 本节交付面窄（1 实现 + 1 测试 + 1 装配），并行空间有限；T002 harness 与 T003 实现同一功能域，宜紧邻推进（先写测试守点、再落实现）。

## Implementation Strategy

- **MVP** = Phase 2（`AgentScheduler` + `AgentSchedulerTest` 四坑，两个关键回归恒绿）——"钟推"第三触发源证明就位。
- Phase 3 一次装配（两个 bean）；Phase 4 门禁 + 报告。
- 每阶段门禁当场修红，不攒到最后。
