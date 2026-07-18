# Implementation Plan: 动态管理 Agent —— 一句话生成、上传即上线

**Branch**: `class-30` | **Date**: 2026-07-18 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/011-agent-lifecycle/spec.md`

## Summary

把 29 节"手写目录 + 重启"升级成"动态管、免重启"：`.oryxos/agents/` 唯一真相源，三条录入（API 上传 / 手工丢目录 / 启动扫描）都汇到同一段 `AgentLifecycleService.register(agentDir)`（= 29 节的 `deriveProfile → ProfileRegistry.register →（有 schedules）AgentScheduler.registerProfile`）。新增 `WorkspaceWatcher`（JDK `WatchService` 守护线程）让"丢目录即上线"；`AgentApiController` 加 generate/create/get/update/delete；`WorkspaceApiController` 只读文件浏览（防目录穿越）；`AgentLifecycleService` 编排 create（失败回滚）/ delete（先注销定时→移索引→归档）；一句话生成走既有 `ProviderService.chat`（配置默认 provider/model）。全部编排复用 29/26 节，唯一新面孔是"一句话生成"与"实时监听"。

## Technical Context

**Language/Version**: Java 21（record + 传统写法；禁增强 switch `default ->`、record 模式、pattern-matching switch；`WatchEvent.kind()` 用等值比较不用模式匹配）

**Primary Dependencies**: Spring Boot 3.x、Spring AI Alibaba（仅协议转换）、JDK `java.nio.file.WatchService`（实时监听，**非第三方**）。**本节不新增任何第三方依赖。**

**Storage**: SQLite + Spring Data JPA（本节**不新增表、不碰 schema**；generate 落既有 `llm_calls` 审计）。

**Testing**: JUnit 5 + Mockito（`InOrder` 钉时序、异常注入钉回滚）+ Spring Test（standalone MockMvc）。单测默认跑。

**Target Platform**: 企业内网 JVM（单 JAR）+ 管理台 SPA。

**Project Type**: Maven 多模块 web（改动集中 oryxos-core 编排 + oryxos-web 端点 + oryxos-cli 接线 + 前端两页）。

**Performance Goals**: 无量化吞吐目标；"丢目录 → 可见"为秒级（WatchService 事件延迟）。

**Constraints**: 同步阻塞 + 虚拟线程，不引入 Reactor/`CompletableFuture`；`WorkspaceWatcher` 是基础设施守护线程（与 25 节 `AgentScheduler` 同类），不把异步引进请求链路；凭证走环境变量。

**Scale/Scope**: 一实例并存任意多 Agent；本节合上"API/一句话/丢目录定义 Agent 并自跑"的闭环，对外只一类资源 Agent。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 影响 | 结论 |
|---|---|---|
| I 自实现 ReAct | 不碰 | ✅ |
| II Spring AI 只协议+Schema | generate 走既有 `ProviderService.chat`，不启用自动 tool | ✅ |
| III Provider 显式映射 | 生成用 provider 走既有 providerMap 显式映射（配置指定 name） | ✅ |
| IV 一个目录=一个 Agent；AGENT.md 归 ContextLoader 不是 Tool | 对外一类资源 Agent（目录）；create 只写 `AGENT.md`；不引入新顶层概念 | ✅ |
| V 审计 Day One | generate 是 LLM 调用，落既有 `llm_calls`；无新增审计缺口 | ✅ |
| VI Sandbox 白名单、不用 SecurityManager | 文件浏览防目录穿越（normalize + startsWith）；不新建沙箱机制 | ✅ |
| VII 同步 + 虚拟线程，无异步框架 | `WorkspaceWatcher` 是基础设施守护线程（同 25 节 scheduler），非请求链路异步 | ✅ |
| VIII Tool 模块三合一 | 不碰 Tool 模块；`AgentLifecycleService`/`AgentStore`/`WorkspaceWatcher` 落 oryxos-core | ✅ |

**无违规**，Complexity Tracking 留空。新增配置键 `oryxos.generate.provider/model` 已经 clarify 用户批准。

## Project Structure

### Documentation (this feature)

```text
specs/011-agent-lifecycle/
├── plan.md · research.md · data-model.md · quickstart.md
├── contracts/internal-api.md
└── tasks.md（/speckit-tasks 产出）
```

### Source Code (repository root)

```text
oryxos-core/src/main/java/io/oryxos/core/agent/
├── AgentLifecycleService.java   # 新增：generate/create(回滚)/register(agentDir)/get/update/delete
├── AgentStore.java              # 新增：write/archive/delete（写 .oryxos/agents/、归档 .oryxos/archive/）
├── WorkspaceWatcher.java        # 新增：WatchService 守护线程 + 可直接调的 handleChange(Path,Kind)
└── AgentScheduler.java          # 改造：+ unregisterProfile(Profile)（用 scheduledTasks 句柄 cancel）

oryxos-web/src/main/java/io/oryxos/web/controller/
├── AgentApiController.java      # 改造：+ generate/create/get/update/delete（invoke 不变）
├── WorkspaceApiController.java  # 新增：GET /workspace/tree · /file（只读、防目录穿越）
└── dto/{AgentView,FileNode,GenerateRequest,CreateAgentRequest,UpdateAgentRequest}.java  # 新增

oryxos-cli/src/main/java/io/oryxos/cli/OryxOsRuntime.java   # 改造：新 Bean 接线 + WorkspaceWatcher(initMethod=start) + 生成 provider/model 注入
oryxos-cli/.../GenerateProperties.java（或复用 config）      # 新增：oryxos.generate.provider/model 绑定

oryxos-web/src/main/frontend/src/App.vue   # 改造：加"Agent 管理""工作区（文件浏览器）"两页
.oryxos/archive/                           # 删除归档区（运行时目录）
```

**Structure Decision**: 编排与基础设施（`AgentLifecycleService`/`AgentStore`/`WorkspaceWatcher`）落 oryxos-core（与 29 节 `AgentLoader`、25 节 `AgentScheduler` 同层，POJO 可单测）；端点与 DTO 落 oryxos-web；接线与守护线程起停落装配层 oryxos-cli。`WorkspaceWatcher` 放 core 而非 cli 是为可单测（`handleChange` 直接调）——**模块结构演进声明**（宪法 v1.1.0）：它跟随 Agent 生命周期能力域，与 `AgentScheduler` 同类，不新建模块。依赖方向不变（web/cli 吃 core）。

## 关键设计决策（详见 research.md）

- **D1 三录入一段代码**：`AgentLifecycleService.register(agentDir)` = `agentLoader.deriveProfile → profileRegistry.register →（schedules 非空）agentScheduler.registerProfile`。API create、`WorkspaceWatcher` 事件都调它；启动扫描仍是 29 节 `AgentLoader.loadAll`（同款派生 + 注册）。
- **D2 create 回滚 / delete 时序**：create 写目录后任一步抛异常 → `agentStore.delete(dir)` 回滚上抛；name 冲突在 `profileRegistry.exists` 第一步就拒、不写目录。delete 恒序 `unregisterProfile → remove → archive`；update 改 schedules 先 `unregisterProfile` 再 `registerProfile`。
- **D3 一句话生成（clarify 已定）**：新增 `oryxos.generate.provider`+`oryxos.generate.model`（provider 缺省取 `oryxos.providers` 第一个）。`generate` 构造"生成用 Profile"（provider/model=配置值）+ `ProviderRequest.of(AGENT_AUTHOR_PROMPT + 一句话)`，调既有 `ProviderService.chat(genSessionId, 生成Profile, req).text()`（落 `llm_calls`）；产出用 `AgentMarkdown.split` + `deriveProfile` 校验能否解析，非法 → `IllegalArgumentException`（400）。只返回文本、不落盘、不注册。**无 `ProviderService.complete`（不存在）**。
- **D4 错误码对齐既有（H3 已核实）**：400 用 `IllegalArgumentException`，404 用 `io.oryxos.web.error.ResourceNotFoundException`（GlobalExceptionHandler 已映射），不新造异常类型；统一 `ApiResponse` 信封。
- **D5 AgentScheduler.unregisterProfile**：遍历 `profile.schedules()`，从既有私有 `scheduledTasks` 取 `ScheduledFuture` 调 `cancel(false)` 再移除句柄；不动 `taskLocks`。这是给 29 节类加新方法（课件列明的本节改造点）。
- **D6 防目录穿越**：`WorkspaceApiController.file` 把 `path` 相对 `oryxosRoot` 解析、`normalize()` 后校验 `startsWith(oryxosRoot)`，越界 → `IllegalArgumentException`（400）；`tree` 只读列 `agents/`+`archive/`。
- **D7 WorkspaceWatcher**：JDK `WatchService` 注册在 `.oryxos/agents/`；`start()` 起守护线程（cli `@Bean(initMethod="start")`，同 `ThreadPoolTaskScheduler`）；事件循环调可单测的 `handleChange(Path,Kind)`；单个坏目录 `try/catch` 跳过不拖垮。

## Complexity Tracking

> 无 Constitution 违规，留空。
