# Implementation Plan: ReAct 循环——Agent 的大脑

**Branch**: `class-17`（用户指定） | **Date**: 2026-07-10 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/002-react-loop/spec.md`

## Summary

自实现 ReAct 主循环（宪法 I）：`ReActLoop` 只做调度（转圈/判停/累积），`PromptBuilder` 拼四段上下文（`ContextLoader` 供给 system 部分），`ToolExecutor` 独占工具执行并写 `tool_invocations` 审计，`AgentService` 作统一处理入口并管理 `ProfileContext`（ThreadLocal）生命周期。模型调用复用第 16 节 `ProviderService`。关键架构决策（research D1）：Provider 调用契约（接口 + 值对象 + 审计接口）上移 oryxos-core，打破 core→provider 的依赖环，并顺带解除 16 节遗留的 storage→provider 反向依赖。

## Technical Context

**Language/Version**: Java 21（virtual thread 场景，全程同步阻塞——宪法 VII）

**Primary Dependencies**: Spring Boot 3.3.5（根 pom 锁定）、Spring AI 1.0.0-M6（仅 16 节 provider 实现模块使用，本节 core 代码零 Spring AI 依赖）、SLF4J/Logback

**Storage**: SQLite + Spring Data JPA；`tool_invocations` 表走手工 `schema.sql` 追加（宪法 VIII，禁 ddl-auto=update）

**Testing**: JUnit 5 + Mockito 5.11（Boot 3.3.5 BOM，inline MockMaker 默认开启，可 mock 具体类）；五个单测类全替身不碰网络；`ToolInvocationRepositoryTest` 复用 16 节 `@DataJpaTest` + SQLite 文件库 + `sql.init.mode=always` 模式

**Target Platform**: JVM 21（企业内网服务器 / K8s）

**Project Type**: Maven 多模块（本节触及 oryxos-core、oryxos-provider（契约上移）、oryxos-storage）

**Performance Goals**: 单请求同步链路；循环上限 max_iterations（默认 10）硬兜底，无额外性能目标

**Constraints**: 不引入 Reactor/CompletableFuture/自建线程池；测试方法名英文 + `@DisplayName` 保课件中文原名（本节起新规）；避开 P3C/ASM 解析不了的 Java 18+ 语法形态；日志参数一律 `replace('\r','_').replace('\n','_')` 字符形态消毒（FindSecBugs CRLF 门禁）

**Scale/Scope**: 6 个 core 新类型 + 3 个前向最小类型（Session/Message/SessionManager）+ 契约上移 5 个文件 + 1 张审计表 + 6 个测试类

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | 原则 | 本节符合性 |
|---|---|---|
| I | 自实现 ReAct Loop | ✅ 本节主体即自实现循环，不用任何框架 Agent 封装；`ReActLoop` 手写 for 循环约几十行 |
| II | Spring AI 只做协议转换 + schema 生成 | ✅ 本节 core 代码不 import Spring AI；工具执行只发生在 `ToolExecutor`（16 节已用 proxyToolCalls=TRUE 关自动执行） |
| III | Provider 显式映射 | ✅ 不触碰映射逻辑，经由 16 节 `ProviderService` 调用 |
| IV | SKILL.md 归 ContextLoader | ✅ 本节交付 `ContextLoader`（core），Skill 文件注入 system prompt，不进 Tool 体系 |
| V | 审计表 Day One 写入 | ✅ `tool_invocations` 本节建表并在 `ToolExecutor` 成败双路径写入；`llm_calls` 复用 16 节 |
| VI | 不用 SecurityManager | ✅ 沙箱检查位留在 `ToolExecutor`（注释注明 24 节接线），不造空壳不碰 SecurityManager |
| VII | 同步执行模型 | ✅ 循环同步阻塞；无异步原语 |
| VIII | SQLite 手工建表 | ✅ `schema.sql` 追加 `tool_invocations`；`ddl-auto=none` |

**Post-design 复查**：D1 契约上移不改变任何运行时行为与方法签名，仅移动包位置与引入接口——8 条原则均不受影响；触发软门禁条款 2/4（改前序交付物位置），已列入 tasks 停点确认项。

## Project Structure

### Documentation (this feature)

```text
specs/002-react-loop/
├── plan.md              # 本文件
├── research.md          # Phase 0：D1~D7 决策
├── data-model.md        # Phase 1：Session/Message/ToolInvocation/上下文组装产物
├── quickstart.md        # Phase 1：验证指引
├── contracts/           # Phase 1：react-loop.md（六个契约面）
└── tasks.md             # /speckit-tasks 产出（本命令不生成）
```

### Source Code (repository root)

```text
oryxos-core/
├── src/main/java/io/oryxos/core/
│   ├── provider/                    # 【D1 契约上移，自 io.oryxos.provider 移入】
│   │   ├── ProviderService.java     #   接口化：chat(String, Profile, ProviderRequest) 签名逐字保真
│   │   ├── ProviderRequest.java     #   值对象（原样移动）
│   │   ├── ProviderResponse.java    #   值对象（原样移动）
│   │   ├── ToolCallRequest.java     #   值对象（原样移动）
│   │   ├── Usage.java               #   值对象（原样移动）
│   │   └── LlmCallAuditor.java      #   审计接口（原样移动）
│   ├── session/                     # 【前向最小，18 节补全——停点确认项②】
│   │   ├── Session.java             #   领域对象：id/profileName/按序消息累积
│   │   ├── Message.java             #   值对象：role + content（+toolName 供工具结果）
│   │   └── SessionManager.java      #   最小接口：仅 save(Session)
│   ├── agent/                       # 【本节交付物】
│   │   ├── ReActLoop.java           #   run(Session, String, Profile) → String
│   │   ├── PromptBuilder.java       #   build(Session, Profile) → ProviderRequest
│   │   ├── ToolExecutor.java        #   execute(sessionId, ToolCallRequest) → ToolResult
│   │   ├── ToolInvocationAuditor.java #  tool_invocations 审计接口（交付物"表"的写入口）
│   │   ├── AgentService.java        #   process(Session, String) → String
│   │   └── ProfileContext.java      #   ThreadLocal set/current/clear
│   └── context/
│       └── ContextLoader.java       # 【本节交付物】Bootstrap+Skill 加载，无缓存
└── src/test/java/io/oryxos/core/
    ├── agent/  ReActLoopTest / PromptBuilderTest / ToolExecutorTest / AgentServiceTest
    └── context/ ContextLoaderTest

oryxos-provider/
└── src/main/java/io/oryxos/provider/
    └── SpringAiProviderService.java # 原 ProviderService 具体类改名，implements core 接口；行为零变化

oryxos-storage/
├── pom.xml                          # 移除对 oryxos-provider 的依赖（D1 副产品）
└── src/main/
    ├── java/io/oryxos/storage/
    │   ├── ToolInvocation.java          # 【本节交付物】JPA 实体
    │   ├── ToolInvocationRepository.java# 【本节交付物】
    │   └── JpaToolInvocationAuditor.java# ToolInvocationAuditor 实现（自吞异常记 ERROR，口径同 16 节）
    └── resources/schema.sql             # 追加 tool_invocations DDL
```

**Structure Decision**: Maven 多模块既有布局；本节新代码按包分层进 oryxos-core（agent/session/context/provider 四包），存储侧与 16 节 `LlmCall` 完全同构。

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| 修改前序节交付物位置（契约上移 core，软门禁 2/4） | core 的 `ReActLoop` 必须调用 Provider，而 provider→core 依赖已存在，直接调用成 Maven 循环依赖，无法编译 | 方案 B（core 另立文档外窄接口）违 H5"不建文档外抽象层"且造成两套值对象；方案 C（ReActLoop 挪出 core）违 TechSol §10 落位表。详见 research D1，**动手前经 tasks 停点用户确认** |
| 前向最小类型 Session/Message/SessionManager（清单外，软门禁 1） | 循环签名 `run(Session, ...)`、入口 `process(Session, ...)`、持久化 `sessionManager.save(session)` 均为课件已定字面量，没有 Session 本节无法成立 | 出处充分：TechSol §10 明文 oryxos-core 含 `Session`；18 节交付 JPA 实体/Repository/getOrCreate 完整签名。本节只立最小面（save），不预支 18 节内容。**tasks 停点确认** |
