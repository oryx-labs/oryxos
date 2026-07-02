# Implementation Plan: Provider 抽象 + ReAct 循环

**Branch**: `001-provider-react-loop` (feature dir; working on `week-01`) | **Date**: 2026-07-01 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-provider-react-loop/spec.md`

## Summary

交付 OryxOS 的第一条端到端主干：用户在 `oryxos chat` 里进行多轮对话，Agent 通过统一的
Provider 抽象调用大模型；由**自实现的 ReAct 循环**驱动「组装 Prompt → 调模型 → 检测工具调用
→ 执行 → 回填 → 继续」直到最终回答或到达迭代上限。本特性交付 Provider 显式映射 + ReActLoop +
会话（内存态）+ 审计落库，但**不交付任何具体工具**——工具调用调度机制以测试替身验证，真实工具
与「查天气穿衣」Demo 延后。技术路径：Spring AI Alibaba 仅做协议转换与 `@Tool` schema 生成，
工具调度/执行完全由 `ReActLoop` + `ToolExecutor` 掌控（宪法原则 I、II）。

## Technical Context

**Language/Version**: Java 21（虚拟线程）

**Primary Dependencies**: Spring Boot 3.3.5；Spring AI（`ChatModel` 协议适配，仅用于调用与
`@Tool` schema）；Spring AI Alibaba starter（DashScope 等 Provider，autoconfig 已按原则 II 排除）；
Picocli（CLI）；SnakeYAML（Profile）；Jackson（消息/工具 JSON）

**Storage**: 会话历史仅内存（本特性，见 Clarification Q1）；审计表 `llm_calls` /
`tool_invocations` 写入 SQLite（`oryxos-storage`，原则 V — Day One 落库）

**Testing**: JUnit 5 + Mockito；ReAct 循环与工具调度用**测试替身**（fake `ChatModel` /
fake `ToolExecutor`）验证，不依赖真实模型或真实工具

**Target Platform**: Linux / macOS 服务器，单可执行 fat JAR（`oryxos-boot`）

**Project Type**: 单体多模块（既有 9 模块 Maven 骨架）；本特性以 CLI 为交付渠道

**Performance Goals**: 交互式对话，无高吞吐目标；并发靠 Java 21 虚拟线程承载（原则 VII）；
单次提问在配置的迭代上限（默认 10）内终止

**Constraints**: 全程同步阻塞，不引入 Reactor/WebFlux/CompletableFuture（原则 VII）；
凭证走 `${ENV_VAR}`，不落明文（原则 VI）；表结构不依赖 Hibernate 自动迁移（原则 VIII）

**Scale/Scope**: 本特性范围 = 3 个用户故事（US1 多轮对话、US2 工具调度机制、US3 Provider 切换）；
涉及模块 `oryxos-core`、`oryxos-provider`、`oryxos-channel-cli`、`oryxos-cli`、`oryxos-storage`

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | 原则 | 本计划的遵循方式 | 判定 |
|---|------|------------------|------|
| I | 自实现 ReAct 循环 | `ReActLoop` 手写主循环；不使用 Spring AI Agent 抽象或 `ChatClient` 自动工具执行 | ✅ PASS |
| II | Spring AI 仅协议转换 + Schema | 仅用 `chatModel.call(Prompt)` 与 `@Tool` schema 生成；tool call 自己解析执行；DashScope autoconfig 已排除 | ✅ PASS |
| III | Provider 显式映射 | `ProviderService` 持 `Map<String, ChatModel>`，按 Profile 的 provider name 路由，不扫描 Bean 类型 | ✅ PASS |
| IV | SKILL.md 归 ContextLoader | 本特性不涉及 SKILL.md；`ContextLoader` 组装 system prompt，不注册为 Tool | ✅ PASS（N/A 但不违背） |
| V | 审计 Day One 落库 | `llm_calls` + `tool_invocations` 本特性即写 SQLite；不靠日志反解析 | ✅ PASS |
| VI | 安全是地基 / 沙箱白名单 | 凭证走 env var；本特性无真实工具故无沙箱执行，但 `ToolExecutor` 抽象预留 `SandboxChecker` 接缝；不用 SecurityManager | ✅ PASS |
| VII | 同步 + 虚拟线程 | ReActLoop 全同步；无异步框架；虚拟线程由 Boot 层开启 | ✅ PASS |
| VIII | 配置即 Agent / 状态外置 | Agent 由 Profile YAML 定义；会话状态经 `SessionManager` 接口访问（本特性内存实现，后续可换 SQLite） | ✅ PASS |

**结论**：无违背项，Complexity Tracking 留空。可进入 Phase 0。

## Project Structure

### Documentation (this feature)

```text
specs/001-provider-react-loop/
├── plan.md              # 本文件
├── research.md          # Phase 0：决策记录
├── data-model.md        # Phase 1：实体模型
├── quickstart.md        # Phase 1：验收/运行指引
├── contracts/           # Phase 1：模块接口契约
│   ├── oryx-tool.md
│   ├── tool-executor.md
│   ├── provider-service.md
│   ├── react-loop.md
│   └── cli-chat.md
└── tasks.md             # Phase 2：/speckit-tasks 生成（本命令不建）
```

### Source Code (repository root)

沿用既有 9 模块骨架，本特性落点如下（只加类，不改模块边界，符合「新增不改 core 边界」）：

```text
oryxos-core/src/main/java/io/oryxos/core/
├── OryxTool.java            # 既有：工具统一抽象
├── ToolResult.java          # 既有：success/content/errorMessage/retryable
├── session/
│   ├── Session.java         # 会话（消息历史 + Profile 引用 + 状态）
│   ├── Message.java         # 消息（user/assistant/tool_call/tool_result）
│   └── SessionManager.java  # 会话读写接口（本特性内存实现）
├── profile/
│   ├── Profile.java         # Agent 定义（identity/provider/tools/settings）
│   └── ProfileLoader.java   # YAML 解析 + 必填校验
├── context/
│   ├── ContextLoader.java   # system prompt 组装（identity + bootstrap）
│   └── PromptBuilder.java   # 组装 [system, 历史, 工具列表] → Prompt
├── tool/
│   ├── ToolRegistry.java    # 可用工具查询（本特性可为空注册表）
│   └── ToolExecutor.java    # 工具执行 + 写 tool_invocations（预留 SandboxChecker 接缝）
└── react/
    └── ReActLoop.java       # 自实现主循环（迭代上限、终止、回填）

oryxos-provider/src/main/java/io/oryxos/provider/
└── ProviderService.java     # Map<String, ChatModel> 显式映射 + 调用 + 写 llm_calls

oryxos-storage/src/main/java/io/oryxos/storage/
├── LlmCallRepository.java        # 审计写入
├── ToolInvocationRepository.java # 审计写入
└── schema/ (建表脚本，不用 hibernate ddl 迁移)

oryxos-channel-cli/src/main/java/io/oryxos/channel/cli/
└── ChatChannel.java         # 交互式 REPL：读输入 → AgentService → 打印

oryxos-cli/src/main/java/io/oryxos/cli/
└── command/ChatCommand.java # `oryxos chat [--profile]` 子命令

oryxos-core/src/main/java/io/oryxos/core/
└── AgentService.java        # 编排：Session + PromptBuilder + ReActLoop 的门面

tests/ → 各模块 src/test/java（JUnit5 + Mockito，fake ChatModel / fake ToolExecutor）
```

**Structure Decision**: 单体多模块，复用既有骨架。核心抽象全部落 `oryxos-core`（ReActLoop、
PromptBuilder、ToolExecutor、SessionManager、AgentService），Provider 落 `oryxos-provider`，
审计落 `oryxos-storage`，CLI 交互落 `oryxos-channel-cli` + `oryxos-cli`。模块依赖方向单向指向
`oryxos-core`，新增 Channel/Tool 只加模块不改 core（技术栈与架构约束）。

## Complexity Tracking

> 无宪法违背项，无需记录。
