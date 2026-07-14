# Implementation Plan: 定时任务模块——让 Agent 到点自己干活

**Branch**: `class-25` | **Date**: 2026-07-12 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/008-scheduled-tasks/spec.md`

## Summary

新增 `AgentScheduler`（落 `oryxos-core`）——定时任务这个第三触发源（钟推）。启动时扫描所有 Profile 的 `schedules`，逐条用 Spring `ThreadPoolTaskScheduler` + `CronTrigger(cron, ZoneId)` 动态注册；到点触发 `runOnce`：按任务 id 取进程内 `ReentrantLock`，`tryLock` 拿不到就跳过（防重叠），拿到锁则以固定三元组 `("scheduler","scheduler",profileName)` 取会话、交给既有 `AgentService.process`（复用第16/17节 llm_calls/tool_invocations 审计），失败只记日志不外抛，`finally` 必放锁。`ScheduleConfig`/`Profile.schedules` 第16节已建全、本节只消费。装配落 `oryxos-cli` 的 `OryxOsRuntime`。

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring `org.springframework.scheduling`（`TaskScheduler`/`ThreadPoolTaskScheduler`/`CronTrigger`）——由 oryxos-core 既有 `spring-boot-starter` 传递带入 `spring-context 6.0.14`，**本节不新增第三方依赖**。javap 已核实 `CronTrigger(String, ZoneId)` 与 `TaskScheduler.schedule(Runnable, Trigger)` 存在。

**Storage**: 无表变更；失败审计复用第16/17节 `llm_calls`/`tool_invocations`（走 `AgentService.process` 内部）。

**Testing**: JUnit 5 + Mockito（mock `TaskScheduler` + `ArgumentCaptor` 抓注册 Trigger；mock `AgentService`/`SessionManager`；`verify(never())`/`times(2)` 断言重叠跳过与锁释放）。

**Target Platform**: 企业内 K8s / 服务器；定时随 `serve`/`gateway` 常驻调度（§8.5）。

**Project Type**: 单体多模块 Maven；核心类落 `oryxos-core`，装配落 `oryxos-cli`。

**Performance Goals**: 无——`runOnce` 微秒级取锁 + 一次同步 ReAct 调用；调度触发由框架负责。

**Constraints**: 同步阻塞（`runOnce` 内 `agentService.process` 同步跑，宪法 VII）；进程内单实例锁，**非**分布式锁；避开 P3C/ASM 不解析的 Java 18+ 语法。

**Scale/Scope**: 新增 1 类（`AgentScheduler`）+ 1 测试类（`AgentSchedulerTest`）；改 1 处装配（`OryxOsRuntime` 加 2 个 bean）；`ScheduleConfig`/`Profile.schedules` 零改动。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 本节相关性 | 结论 |
|---|---|---|
| V 审计 Day One 落库 (NON-NEGOTIABLE) | 钟推失败要留痕 | ✅ `runOnce` 走 `AgentService.process` 内部既有 llm_calls/tool_invocations 审计；失败额外 `log.error`，不新增审计逻辑 |
| VII 同步执行 + 虚拟线程，不引入异步编程模型 | 用了 TaskScheduler 线程池 | ✅ `ThreadPoolTaskScheduler` 是**框架的调度机制**（§8.5 明确指定），非 Reactor/WebFlux/CompletableFuture；`runOnce` 内部同步阻塞调 `process`。调度线程池≠ReAct 循环里的异步编程模型，也非"自建线程池" |
| VIII 配置即 Agent、状态外置 | 定时规则来自配置 | ✅ 触发规则读 Profile.schedules（YAML），`TaskScheduler.schedule(...)` 动态注册，不用编译期写死的 `@Scheduled` |
| III/IV 模块边界 | AgentScheduler 落哪 | ✅ 落 `oryxos-core`（§10 + §8.5 明确归 core），与 AgentService 同包；不新建模块 |
| I/II/VI | 不涉及 ReAct 内核/Provider/Sandbox 改动 | N/A（只调既有 AgentService 入口） |

**H4 不变量对齐**：④ `session_id` 只在 `SessionManager` 内拼——本模块只传三元组字符串给 `sessionManager.getOrCreate(...)`，不自己拼 session_id；⑤ 无 Reactor/CompletableFuture/自建线程池——用的是框架 TaskScheduler，`runOnce` 同步。

**Gate: PASS**（无违规，无需 Complexity Tracking）。改造点声明：`OryxOsRuntime` 新增 2 个 @Bean（TaskScheduler + AgentScheduler）——标准装配，非改前序公共接口。

## Project Structure

### Documentation (this feature)

```text
specs/008-scheduled-tasks/
├── plan.md              # 本文件
├── research.md          # Phase 0：调度 API 选型 + 5 处实现决策
├── data-model.md        # Phase 1：AgentScheduler 结构 + 复用的 ScheduleConfig
├── quickstart.md        # Phase 1：真实到点触发 + harness 验证入口
├── contracts/
│   └── scheduler.md     # Phase 1：registerAll/runOnce/lockFor 行为契约
└── tasks.md             # Phase 2（/speckit-tasks 生成，非本命令）
```

### Source Code (repository root)

```text
oryxos-core/src/main/java/io/oryxos/core/agent/
├── AgentScheduler.java             # 【新增】registerAll + runOnce + lockFor + taskLocks 表
├── AgentService.java               # 【不改】runOnce 调其 process
oryxos-core/src/main/java/io/oryxos/core/profile/
└── Profile.java                    # 【不改】ScheduleConfig 嵌套 record + schedules 字段（第16节已建全）

oryxos-cli/src/main/java/io/oryxos/cli/
└── OryxOsRuntime.java              # 【改】+ ThreadPoolTaskScheduler bean（daemon+initialize）+ AgentScheduler bean（initMethod=registerAll）

oryxos-core/src/test/java/io/oryxos/core/agent/
└── AgentSchedulerTest.java         # 【新增】四坑：cron+时区注册参数 / 重叠跳过 / 失败不外抛+锁释放 / 会话三元组
```

**Structure Decision**: `AgentScheduler` 落 `oryxos-core.agent`（与 `AgentService` 同包，§8.5/§10 归属）；装配落 `oryxos-cli`。无新建模块。

## Complexity Tracking

> 无宪法违规，本节留空。
