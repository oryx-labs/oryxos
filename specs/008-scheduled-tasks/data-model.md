# Data Model: 定时任务模块

无持久化实体、无表变更。以下是内存结构与复用的既有配置载体。

## 复用（第16节交付，本节不改）

### Profile.ScheduleConfig（嵌套 record）

```java
public record ScheduleConfig(String id, String cron, String zone, String message) {}
```

- `id`：任务标识（锁维度、日志维度）。
- `cron`：cron 表达式。
- `zone`：时区字符串（如 `Asia/Shanghai`）；空/blank → 默认系统时区。
- `message`：到点发给 Agent 的消息。

### Profile.schedules（字段）

`List<ScheduleConfig> schedules`——已建全并防御性拷贝（`List.copyOf`）。`profile.schedules()` 访问。

## 新增：AgentScheduler（POJO，落 oryxos-core.agent）

| 字段 | 类型 | 来源 | 说明 |
|------|------|------|------|
| `taskScheduler` | `ThreadPoolTaskScheduler` | 构造注入 | 框架调度器（daemon+initialized） |
| `profileRegistry` | `ProfileRegistry` | 构造注入 | `all()` 取全部 Profile |
| `agentService` | `AgentService` | 构造注入 | `process(session, message)` 统一入口 |
| `sessionManager` | `SessionManager` | 构造注入 | `getOrCreate(channel,user,profile)` |
| `taskLocks` | `ConcurrentMap<String, Lock>` | 内部 | 任务 id → ReentrantLock，防重叠 |

**方法**：

- `void registerAll()`：遍历 `profileRegistry.all()` → 每个 `profile.schedules()` → 每条 `sc`，`taskScheduler.schedule(() -> runOnce(profile, sc), new CronTrigger(sc.cron(), resolveZone(sc.zone())))`；单条 try/catch 记 WARN 跳过（FR-007）。启动装配调一次。
- `void runOnce(Profile profile, ScheduleConfig sc)`：`lockFor(sc.id()).tryLock()` 拿不到→INFO 跳过 return；拿到→`try{ session=getOrCreate("scheduler","scheduler",profile.name()); agentService.process(session, sc.message()); } catch(Exception e){ log.error } finally{ unlock() }`。public 供测试直接调。
- `Lock lockFor(String taskId)`：`taskLocks.computeIfAbsent(taskId, id -> new ReentrantLock())`。public 供测试占锁。
- `private ZoneId resolveZone(String zone)`：blank → `ZoneId.systemDefault()`，否则 `ZoneId.of(zone)`。

**常量**：`SCHEDULER_CHANNEL = "scheduler"`、`SCHEDULER_USER = "scheduler"`（会话三元组固定值，模块内部约定，非对外概念）。

## 改造：OryxOsRuntime（oryxos-cli 装配）

| 新增 bean | 说明 |
|-----------|------|
| `ThreadPoolTaskScheduler taskScheduler()` | `setDaemon(true)` + `initialize()`（Edge Case：不阻塞 chat 退出） |
| `AgentScheduler agentScheduler(...)` | `@Bean(initMethod="registerAll")`，注入 taskScheduler/profileRegistry/agentService/sessionManager |
