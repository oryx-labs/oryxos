# Contract: AgentScheduler

`AgentScheduler` 是钟推触发源，只对外做"到点拼消息交给 AgentService"。三个 public 方法（`registerAll`/`runOnce`/`lockFor`），其中 `runOnce`/`lockFor` public 是为可测（直接调、抓锁），非对外 API。

## registerAll()

**时机**：启动装配（`@Bean(initMethod)`）调一次。

**行为**：遍历所有 Profile 的每条 `ScheduleConfig`，`taskScheduler.schedule(() -> runOnce(profile, sc), new CronTrigger(sc.cron(), zone))`。zone 由 `sc.zone()` 解析（blank→系统默认）。

**失败隔离（FR-007）**：单条 cron/zone 非法（`IllegalArgumentException`/`DateTimeException`）MUST 被 catch、记 WARN、跳过该条，MUST NOT 中断其它条注册。

**关键回归**：注册时传给 `TaskScheduler.schedule` 的 `Trigger` MUST 是携带了配置 cron 与时区的 `CronTrigger`（ArgumentCaptor 抓参数断言）。

## runOnce(Profile profile, ScheduleConfig sc)

**前置**：`sc` 非 null。

**并发控制（FR-004）**：首步 `lockFor(sc.id()).tryLock()`。拿不到（上一次仍在跑）MUST 记 INFO、直接 return，MUST NOT 调 `agentService.process`（本次跳过，不排队、不并行）。

**正常路径（FR-003/006）**：拿到锁 → `session = sessionManager.getOrCreate("scheduler","scheduler",profile.name())` → `agentService.process(session, sc.message())`。会话三元组固定；消息内容/后续处理不由本方法承担。

**失败隔离（FR-005）**：`process` 抛异常 MUST 被 catch、记 log.error、MUST NOT 外抛（调度器不崩）。失败的这次调用的审计由 `process` 内部既有链路负责，本模块不新增审计逻辑。

**锁释放（FR-005）**：无论成功/失败，`finally` MUST `unlock()`。**二进宫回归**：失败一次后再 `runOnce` 一次 MUST 能进入（`times(2)` 证明锁真放了、没死锁）。

## lockFor(String taskId)

按任务 id 返回同一把 `ReentrantLock`（`computeIfAbsent`）。同 id 共享一把锁。

## 边界（本契约不覆盖）

多实例分布式协调（选主/分布式锁/租约）、失败自动重试/告警、运行时增删定时配置——全部扩展阶段。
