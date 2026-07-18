# Implementation Plan: 插件化 Agent —— 一个目录定义一个会自己跑的 Agent

**Branch**: `class-29` | **Date**: 2026-07-18 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/010-folder-agent/spec.md`

## Summary

把"定义一个 Agent"从"手写一份独立 Profile YAML + 硬拼指令"改成"往 `.oryxos/agents/` 丢一个目录"。一个目录 = 一个 Agent：`AGENT.md`（frontmatter = 这个 Agent 的 profile，正文 = 任务指令）+ 可选 `skills/`（子指令）、`scripts/`（脚本）、`REFERENCE.md`（参考）。系统扫描目录区、把每个 `AGENT.md` 的 frontmatter **派生**成底座既有的 `Profile` 值对象并登记，从而零改动复用整台底座（16~28 节）。渐进式披露收进一个 Agent 内部：正文注入 system prompt，子指令 / 参考经底座既有 `read_file` 按需读、脚本经底座既有 `shell` 按需跑——不新造机制、不新增工具、无跨 Agent 能力库 / `use_skill` / 全局索引。同时补运行时注册（去掉"新增 Agent 必须重启"），为 30 节铺路。

## Technical Context

**Language/Version**: Java 21（record 值对象 + 传统写法；禁用增强 switch `default ->`、record 模式、pattern-matching switch —— P3C/ASM 门禁）

**Primary Dependencies**: Spring Boot 3.x、Spring AI Alibaba（仅协议转换 + `@Tool` schema）、SnakeYAML（已在 oryxos-core，`ProfileLoader` 在用）。**本节不新增任何第三方依赖。**

**Storage**: SQLite + Spring Data JPA（本节只经既有 `ToolExecutor` 审计链路，**无新增表、无表结构变更**）。

**Testing**: JUnit 5 + Mockito + Spring Test。单测默认跑；本节无 `@Tag("integration")` 集成类（真模型链路验证在 31 节）。

**Target Platform**: 企业内网 JVM（单 JAR）。

**Project Type**: Maven 多模块（改动集中在 `oryxos-core` + 装配层 `oryxos-cli`；连带触碰 `oryxos-web` 一处视图）。

**Performance Goals**: 无量化吞吐目标（内部运行时内核）。启动扫描 N 个 Agent 目录 = O(N) 文件读，与既有 profiles 扫描同量级。

**Constraints**: 同步阻塞 + Java 21 虚拟线程，不引入 Reactor / `CompletableFuture` / 自建线程池；凭证走环境变量占位、不落明文；`ContextLoader` 每次现读、无缓存（"改正文即时生效"的硬前提）。

**Scale/Scope**: 一台实例并存任意多个 Agent；本节做到"一个目录派生成一个会自己跑的 Agent + 运行时注册就位"，对外动态管理接口留 30 节。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 影响 | 结论 |
|---|---|---|
| I 自实现 ReAct Loop | 复用既有 `ReActLoop`，不碰 | ✅ PASS |
| II Spring AI 只做协议 + Schema | 不涉及 | ✅ PASS |
| III Provider 显式映射 | 复用既有 `providerMap`，不碰 | ✅ PASS |
| IV 一个目录 = 一个 Agent；AGENT.md 归 ContextLoader，不是 Tool | **本节即此原则的落地**：`AgentLoader`/`deriveProfile`/`ContextLoader` 全在 `oryxos-core`，一个 Agent 目录不进 `ToolRegistry`、不进 `oryxos-tool`；无 `use_skill` / 能力库 / 全局索引 | ✅ PASS（本节实现它） |
| V 审计 Day One 落库 | 复用既有 `tool_invocations` / `llm_calls`，无新增审计缺口 | ✅ PASS |
| VI Sandbox 白名单、不用 SecurityManager | 脚本沙箱复用 24 节 `SandboxChecker` 白名单（解释器 + 目录），不新建机制 | ✅ PASS |
| VII 同步 + 虚拟线程，无异步框架 | 不新增任何异步 | ✅ PASS |
| VIII Tool 模块三合一；AGENT.md 加载归 core 的 ContextLoader | `AgentLoader`/`ContextLoader` 落 `oryxos-core`，Tool 模块不动 | ✅ PASS |

**无违规**，Complexity Tracking 留空。

## Project Structure

### Documentation (this feature)

```text
specs/010-folder-agent/
├── plan.md              # 本文件
├── research.md          # Phase 0：设计决策
├── data-model.md        # Phase 1：实体与改造点
├── quickstart.md        # Phase 1：验收/运行指南
├── contracts/           # Phase 1：内部接口契约
│   └── internal-api.md
└── tasks.md             # Phase 2（/speckit-tasks 产出）
```

### Source Code (repository root)

```text
oryxos-core/src/main/java/io/oryxos/core/
├── agent/
│   ├── AgentLoader.java            # 新增：扫 .oryxos/agents/、拆 AGENT.md、deriveProfile、校验/告警
│   ├── AgentMarkdown.java          # 新增：把 AGENT.md 文本拆成 (frontmatter Map, body) 的小工具，AgentLoader 与 ContextLoader 共用
│   ├── AgentScheduler.java         # 改造：registerAll 循环体抽成 registerProfile(Profile) + 新增 scheduledTasks 句柄表
│   ├── PromptBuilder.java          # 改造点回归：system 段随 ContextLoader 变化（不再有 skill 全文/索引）
│   └── ...（ReActLoop/ToolExecutor/AgentService 不动）
├── context/ContextLoader.java      # 改造：注入 AGENT.md 正文(从 agents/<name>/AGENT.md 现读) + 去 skills 循环；ctor 不变(oryxosRoot)
├── profile/
│   ├── Profile.java                # 改造点：移除 skills 字段（canonical 12→11 参）
│   ├── ProfileLoader.java          # 改造：抽出 map→Profile 校验(供 AgentLoader 复用同一异常同一消息)；停解析 skills
│   └── ProfileRegistry.java        # 改造：不可变 → 可变并发 Map + register/remove/exists
└── ...

oryxos-cli/src/main/java/io/oryxos/cli/
├── OryxOsRuntime.java              # 改造：扫 .oryxos/agents/ 派生注册（替换扫 profiles）；ContextLoader/AgentScheduler 接线
└── command/{InitCommand,StatusCommand,ProfileCommand}.java  # 改造：目录 profiles → agents

oryxos-web/src/main/java/io/oryxos/web/controller/
└── ProfileApiController.java       # 改造点回归：视图去 p.skills()

my-agent/.oryxos/agents/daily-reconcile/   # 示例 Agent（第29节课件 §1.3–1.4 规格，由本节产出）
├── AGENT.md
├── scripts/reconcile.py
├── skills/report-format.md
└── REFERENCE.md
```

**Structure Decision**: 改动集中在 `oryxos-core`（新增 2 类 + 改造 4 类）与装配层 `oryxos-cli`；`oryxos-web` 仅一处视图随 `Profile.skills` 移除同步。无跨模块循环依赖，依赖方向不变（下游吃 `oryxos-core` 的 `Profile`）。

## 关键设计决策（详见 research.md）

- **D1 正文注入走"现读"**：`ContextLoader.load` 每次从 `oryxosRoot/agents/<profile.name()>/AGENT.md` 现读、拆掉 frontmatter、注入正文——满足"改正文不重启即时生效"（若在 `deriveProfile` 时把正文固化进 `Profile`，编辑正文要重扫才生效，违反 FR-003/SC-003）。`ContextLoader` ctor 保持 `(oryxosRoot)` 不变，**不引入 SkillRegistry 依赖**（避免波及 `MockProviderFlowTest` 等构造点）。
- **D2 同一异常同一消息**：从 `ProfileLoader.parse` 抽出 `Map<String,Object> → Profile`（含全部字段校验）的可复用入口；`AgentLoader.deriveProfile` 拆出 frontmatter Map 后调它——运行时（AgentLoader）与启动（同一入口）报错完全一致，落实 FR-006 关键回归。
- **D3 移除 `Profile.skills` 字段**（推荐）：终态模型无 skills 概念，物理移除该字段使数据模型与终态一致。影响面已量化：12 处 `new Profile(` + `ProfileLoader`(停解析) + `ContextLoader`(去消费) + `ProfileApiController`(去视图字段)。**这是前序公共类型改造、课件未逐字列为改造点** → 软门禁项，`/speckit-tasks` 停点向用户确认；备选：保留为恒空字段（改动更小但留死字段）。
- **D4 定时来自 Agent**：`schedules` 随 frontmatter 派生进 `Profile.schedules`，`AgentScheduler` 遍历它注册——`registerProfile(Profile)` 抽出 + `scheduledTasks` 句柄表，逻辑等价既有 `registerAll`，为 30 节注销铺路。
- **D5 脚本沙箱与信任边界**：脚本经底座既有 `shell`/`python` 跑，白名单放行解释器 + 限定 `.oryxos/agents/<name>/scripts/`（复用 24 节 `SandboxChecker`）；信任边界（脚本网络绕过 `http_get` 域名白名单 = 信任作者）课件已明确。**脚本真执行的 cwd/路径联调属 31 节 Demo**；本节 harness 只钉"正文进 prompt、子资源不预载靠 read_file/shell 按需取"的机制，不要求脚本在 29 节真跑。
- **D6 `.oryxos/profiles/` 取消**：`OryxOsRuntime` 改扫 `.oryxos/agents/`；`ProfileLoader.loadAll`（扫 profiles 目录）退出接线，其校验入口保留供 `AgentLoader` 复用；`InitCommand`/`StatusCommand`/`ProfileCommand` 的 `profiles` 目录名迁 `agents`。

## Complexity Tracking

> 无 Constitution 违规，本节留空。
