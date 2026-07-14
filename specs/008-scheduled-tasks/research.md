# Research: 定时任务模块

无 NEEDS CLARIFICATION（实现路径由第25节课件 §三 + TechSol §8.5 定死；调度 API 已 javap 核实）。以下记录关键实现决策与依据。

## Decision 1：调度机制用 Spring TaskScheduler + CronTrigger 动态注册，不用 @Scheduled

- **Decision**: `ThreadPoolTaskScheduler.schedule(Runnable, new CronTrigger(cron, ZoneId))` 在 `registerAll()` 里逐条动态注册；不用 `@Scheduled` 注解。
- **Rationale**: `@Scheduled` 的 cron 是编译期写死的常量，改触发时间要改代码重编译，违反"配置即 Agent"（宪法 VIII）。触发规则来自 Profile.schedules（YAML），必须运行时动态注册。§8.5 明确指定 `ThreadPoolTaskScheduler` + `CronTrigger`。
- **API 核实（javap spring-context 6.0.14）**：`CronTrigger(String, ZoneId)` ✓、`CronTrigger(String, TimeZone)` ✓、`TaskScheduler.schedule(Runnable, Trigger)` ✓。选 `ZoneId` 变体（比 TimeZone 现代、少歧义）。
- **Alternatives considered**: `@Scheduled`——否，编译期写死；自造调度器——否，宪法/课件"不重复造轮子"。

## Decision 2：AgentScheduler 为构造注入 POJO，装配处显式注册

- **Decision**: `AgentScheduler` 无 `@Component/@PostConstruct`；构造注入 `ThreadPoolTaskScheduler`/`ProfileRegistry`/`AgentService`/`SessionManager`。`OryxOsRuntime` 里 `@Bean(initMethod = "registerAll")` 触发启动注册。
- **Rationale**: 与 `AgentService`/`ReActLoop` 同构——核心类保持纯 POJO 零框架注解，装配统一由 `OryxOsRuntime` @Bean 显式做（项目既定风格）。`initMethod` 让 Spring 在依赖就绪后调 `registerAll`，等价于 `@PostConstruct` 但不把注解侵入 core。
- **Alternatives considered**: 课件示例的 `@Component + @PostConstruct`——否，会让 core 类带 Spring 注解，破坏既有 POJO 约定。

## Decision 3：ThreadPoolTaskScheduler 线程设为 daemon

- **Decision**: `OryxOsRuntime` 造 `ThreadPoolTaskScheduler` 时 `setDaemon(true)` + `initialize()`。
- **Rationale**: `chat` 是一次性命令（`WebApplicationType.NONE`），跑完对话进程应正常退出；非 daemon 的调度线程会挂住 JVM 不退出（spec Edge Case：定时线程 MUST NOT 阻止一次性命令退出）。daemon 线程不阻止 JVM 关闭；`serve`/`gateway` 常驻时靠主线程 join 保活，daemon 调度线程照常跑。
- **Alternatives considered**: 只在 serve/gateway 注册——否，增加条件分支复杂度；非 daemon——否，会挂住 chat 退出。

## Decision 4：按任务 id 的进程内 ReentrantLock 防重叠，tryLock 跳过

- **Decision**: `ConcurrentMap<String, Lock> taskLocks`，`computeIfAbsent(sc.id(), id -> new ReentrantLock())`；`runOnce` 首步 `tryLock()`，拿不到记 INFO 直接 return；拿到则 `try{...}finally{unlock()}`。
- **Rationale**: 核心阶段单实例，一把内存锁解决"同一进程内同一任务不重叠"就够（§8.5、FR-004）。`tryLock`（非阻塞）实现"跳过不排队"；`finally` 放锁防死锁（FR-005）。
- **Alternatives considered**: 分布式锁（Redis/DB）——否，多实例协调是扩展阶段；阻塞 `lock()`——否，会排队堆积而非跳过。

## Decision 5：失败隔离——catch 记 log.error 不外抛，审计复用既有链路

- **Decision**: `runOnce` 的 `try` 块 `catch (Exception e) { log.error(...) }` 不外抛；`AgentService.process` 内部的 llm_calls/tool_invocations 审计照常写，本模块不新增审计逻辑。
- **Rationale**: 单次失败不能拖垮调度器、不能悄悄失败（FR-005）。审计在 `process` 内部（第16/17节），钟推与人推走同一条，不为钟推单开（宪法 V）。
- **Alternatives considered**: 外抛——否，会让 TaskScheduler 记录异常甚至影响后续触发；单开审计表——否，重复且违背"不为钟推新设概念"。

## Decision 6：会话身份三元组固定，session_id 仍由 SessionManager 内拼

- **Decision**: `sessionManager.getOrCreate("scheduler", "scheduler", profile.name())`；channel/user 固定字面量 `"scheduler"`。
- **Rationale**: 同一 Agent 历次定时触发复用同一 Session、历史靠 `max_history_turns` 截断（FR-006、§8.5）。`session_id` 的拼接仍在 `SessionManager` 内部（H4 不变量④），本模块只传三元组、不碰 session_id 生成。
- **Alternatives considered**: 每次新 Session——否，历史不累积、违背§8.5；在本模块拼 session_id——否，破坏 H4④。

## 依赖 / 语法核实

- 无新增第三方依赖（spring-context 传递自 spring-boot-starter）。
- 语法禁区：`registerAll` 用 for-each + lambda + try/catch（跳过坏规则），`runOnce` 用传统 try/catch/finally + ReentrantLock——均 Java 17 内语法，无增强 switch/record 模式/pattern-matching switch，P3C 安全。
- `ZoneId.of(zone)` 对非法 zone 抛 `DateTimeException`/`ZoneRulesException`；`CronTrigger` 对非法 cron 抛 `IllegalArgumentException`——`registerAll` 单条 try/catch 兜住、跳过（FR-007）。
