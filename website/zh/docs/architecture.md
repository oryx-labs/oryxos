# 架构

OryxOS 是一个 Spring Boot 3.x 单体应用，运行在 JDK 21 上，整个系统——LLM 路由、推理循环、记忆系统、工具体系、REST API——打包在一个 fat JAR 里交付。除了 JVM 和你配置的 LLM Provider 凭证，不需要任何外部依赖。状态存储在 SQLite 和 `.oryxos/` 目录下的本地文件系统中。

推理引擎是自实现的 ReAct Loop。Spring AI Alibaba 负责 LLM 协议转换；循环本身、上下文组装、工具调度和审计记录，都由 OryxOS 自己掌控。

## 架构图

![OryxOS Architecture](/images/architecture.png)

## 分层架构图

```
┌─────────────────────────────────────────────────────┐
│                     接入层                           │
│         CLI (oryxos chat)  │  REST API (/api/v1)    │
│              oryxos-channel-cli  │  oryxos-web       │
└──────────────────────┬──────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────┐
│                     引擎层                           │
│     ReActLoop  │  PromptBuilder  │  ToolExecutor    │
│                    oryxos-core                      │
└──────┬──────────────┬──────────────────┬────────────┘
       │              │                  │
┌──────▼──────┐ ┌──────▼──────┐ ┌───────▼────────────┐
│  Provider   │ │   Memory    │ │    Tool System      │
│  (LLM 调用) │ │  (上下文)   │ │    (执行)           │
│ oryxos-     │ │ oryxos-     │ │  oryxos-tool        │
│ provider    │ │ memory      │ │                     │
└──────┬──────┘ └──────┬──────┘ └───────┬────────────┘
       │               │                │
┌──────▼───────────────▼────────────────▼────────────┐
│                     基础层                           │
│   SQLite (sessions, tool_invocations, llm_calls)   │
│   文件系统 (.oryxos/: profiles, memory, skills)     │
│                   oryxos-storage                    │
└─────────────────────────────────────────────────────┘
```

**接入层** — 两个入口。CLI 处理交互式使用和本地调试。REST API 处理业务系统集成。两者都汇入同一个引擎层。

**引擎层** — Agent 的大脑。`ReActLoop` 驱动每次迭代：组装 Prompt、调用 LLM、检查响应、执行工具、追加结果、继续循环。`PromptBuilder` 在每次迭代中组装四段式 Prompt。`ToolExecutor` 调度工具调用、执行沙箱策略、写入审计记录。

**能力层** — 引擎调用的能力。`ProviderService` 将 LLM 调用路由到配置的 Provider。`MemoryService` 提供对话历史和长期记忆。`ToolRegistry` 存储所有已注册的工具，包括内置工具和 MCP 对接的工具。

**基础层** — 持久化。SQLite 存储会话、工具调用记录和 LLM 调用记录。文件系统存储 Profile YAML、Bootstrap 文件、`MEMORY.md` 和技能文件——凡是用户可能想直接编辑或纳入 git 管理的内容。

## 模块结构

| 模块 | 职责 |
| --- | --- |
| `oryxos-core` | 核心抽象与接口：`OryxTool`、`Session`、`Profile`、`ContextLoader`、`ReActLoop`、`PromptBuilder`、`ToolExecutor`、`AgentService` |
| `oryxos-provider` | LLM 路由：`ProviderService`、Function Calling 格式适配、显式的 `provider name → ChatModel` 映射 |
| `oryxos-memory` | 记忆系统：`MemoryService` 门面、`LongTermMemory`（读写 `MEMORY.md`）、`MemoryTools`（`save_memory`、`recall_memory`） |
| `oryxos-tool` | 工具体系：内置工具（`FileTools`、`ShellTools`、`HttpTools`）、`McpClientService`、`McpToolAdapter`、`ToolRegistry`、`SandboxChecker` |
| `oryxos-channel-cli` | CLI 渠道：`CliChannel`、`oryxos chat` 命令实现 |
| `oryxos-web` | REST API：`WebServer`、六个 `ApiController`、`GlobalExceptionHandler`、OpenAPI 规范 |
| `oryxos-storage` | 持久化：SQLite via Spring Data JPA、`SessionRepository`、`ToolInvocationRepository`、`LlmCallRepository` |
| `oryxos-cli` | CLI 入口：Picocli 主入口、12 个子命令、`ConfigLoader`（凭证和配置校验） |
| `oryxos-boot` | Spring Boot 启动模块：主类、自动配置、依赖聚合 |

模块之间通过接口通信。新增 Channel 或 Tool 实现只需新建模块，`oryxos-core` 不受影响。

## 关键技术决策

| # | 决策 | 选择 | 理由 |
| --- | --- | --- | --- |
| 1 | ReAct Loop 实现方式 | 自实现；不使用 Spring AI 的 Agent 抽象 | 完全掌控循环行为；避免工具被隐式执行两次 |
| 2 | Spring AI 使用边界 | 仅用于协议转换和 `@Tool` JSON Schema 生成；显式禁用自动工具执行 | Spring AI 的自动执行会让工具跑两遍——Spring AI 一次，`ToolExecutor` 一次 |
| 3 | 执行模型 | 同步阻塞 + Java 21 虚拟线程 | 代码直观，不引入响应式编程复杂度，同时具备高并发能力 |
| 4 | 工具注册方式 | `@Tool` 注解生成 Schema + `OryxTool` 抽象层 | 内置工具、`@Tool` 注解工具、MCP 工具统一接口；`ReActLoop` 不关心工具来自哪里 |
| 5 | HTTP 服务层 | Spring MVC + Java 21 虚拟线程 | 同步风格的代码，单节点支持数千并发请求；扩展阶段可用 `SseEmitter` 支持流式 |
| 6 | 沙箱策略 | 应用层路径/模式白名单 | `SecurityManager` 从 JDK 17 起废弃、JDK 21 上不可用；完整的容器级沙箱隔离是扩展阶段的工作 |
| 7 | 持久化方案 | SQLite + Spring Data JPA 存关系型数据；`MEMORY.md` 文件存长期记忆 | 单体部署无需外部数据库进程；审计表从第一天就写入，审计能力不会是事后追加的 |

## 技术栈

| 组件 | 选型 |
| --- | --- |
| 语言 / 运行时 | Java 21（必须；虚拟线程，无 SecurityManager） |
| 框架 | Spring Boot 3.x |
| LLM 集成 | Spring AI + Spring AI Alibaba（仅用于协议转换和 `@Tool` Schema 生成） |
| HTTP 服务 | Spring MVC + Java 21 虚拟线程 |
| 命令行 | Picocli |
| YAML 解析 | SnakeYAML |
| 持久化 | SQLite + Spring Data JPA（初始建表用 `hibernate.ddl-auto=update`；表结构变更手动维护迁移脚本） |
| 长期记忆 | `MEMORY.md` 平文件 + 关键词检索；扩展阶段规划向量检索 |
| MCP 集成 | MCP Java SDK（MCP Client 用于连接外部 MCP server） |
| 日志 | Logback + SLF4J，结构化 JSON 输出 |
| 构建 | Maven 多模块 |
| 未来：原生二进制 | GraalVM Native Image（扩展阶段，启动时间从约 3 秒降至 100ms 以下） |
| 未来：可观测性 | Micrometer + Prometheus（扩展阶段） |
