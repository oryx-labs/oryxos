# 架构

OryxOS 是一个 Spring Boot 3.x 单体应用，运行在 JDK 21 上，整个系统——LLM 路由、推理循环、记忆系统、工具体系、REST API 以及 Web 管理台——打包在一个 fat JAR 里交付。除了 JVM 和你配置的 LLM Provider 凭证，不需要任何外部依赖。状态存储在 SQLite 和工作区目录下的本地文件系统中（默认 `.oryxos/`，可配置——见下文）。

推理引擎是自实现的 ReAct Loop。Spring AI Alibaba 负责 LLM 协议转换；循环本身、上下文组装、工具调度和审计记录，都由 OryxOS 自己掌控。

## 架构图

![OryxOS Architecture](/images/architecture.svg)

## 分层架构图

![OryxOS Layer Diagram](/images/layer-diagram.svg)

**接入层** — 系统入口。CLI 处理交互式使用和本地调试。REST API 处理业务系统集成，同时支撑 Web 管理台。两者都汇入同一个引擎层。

**引擎层** — Agent 的大脑。`ReActLoop` 驱动每次迭代：组装 Prompt、调用 LLM、检查响应、执行工具、追加结果、继续循环。`PromptBuilder` 在每次迭代中组装四段式 Prompt。`ToolExecutor` 调度工具调用、执行沙箱策略、写入审计记录。

**能力层** — 引擎调用的能力。`ProviderService` 将 LLM 调用路由到配置的 Provider，运行时按名称从 Provider 注册表动态解析。`MemoryService` 提供对话历史和长期记忆。`ToolRegistry` 存储所有已注册的工具，包括内置工具和 MCP 对接的工具。

**基础层** — 持久化。SQLite 存储会话、工具调用与 LLM 调用审计记录、定时任务及其执行历史、每个 Agent 的记忆条目、通知渠道和 Provider 定义。文件系统存储 Agent 目录（`AGENT.md` 及可选的 `skills/`、`scripts/`、`REFERENCE.md`）、Bootstrap 文件和 `MEMORY.md`——凡是用户可能想直接编辑或纳入 git 管理的内容。

## 一个目录 = 一个 Agent

每个 Agent 就是一个目录：`.oryxos/agents/<name>/`。不再有独立的 `.oryxos/profiles/`——Profile 本身就是这个 Agent。

- `AGENT.md` = YAML frontmatter（这个 Agent 的 Profile：`name`、`description`、`identity`、`provider`/`model`、`tools`、`mcp_servers`、`channels`、`bootstrap`、`settings`、`schedules`）+ 正文任务指令（注入 system prompt）。`AgentLoader.deriveProfile(agentDir)` 把 frontmatter 派生成底座认识的 `Profile`。
- 同目录下可选的 `skills/*.md` 子指令、`scripts/`、`REFERENCE.md` 通过底座既有的 `read_file` / `shell` 工具**按需加载**——没有全局能力索引，没有 `use_skill`。
- 长期记忆是**每个 Agent 独立的**：`.oryxos/agents/<name>/MEMORY.md`（无 Agent 上下文时回退到全局 `.oryxos/memory/MEMORY.md`）。记忆行带时间戳，且每次触发某个 Agent 都会自动记入该 Agent 的归档记忆。

## 动态 Agent 生命周期

Agent 可在运行时创建、更新、移除——无需重启。

- **REST CRUD** — `POST /api/v1/agents` 写入 `.oryxos/agents/<name>/AGENT.md`、派生 Profile 并注册（失败回滚）；`PUT` 更新（调度变更会先注销再重新注册）；`DELETE` 是软删除：取消调度 → 从注册表移除 → 归档目录（绝不物理删除）。
- **拖拽即上线（WorkspaceWatcher）** — 基于 JDK `WatchService` 的监听器观察 `.oryxos/agents/`。往里放一个目录，Agent 立即生效；该监听器也让注册表与磁盘上的编辑保持同步。
- **一句话生成** — `POST /api/v1/agents/{name}/generate-files` 用一句话经 LLM 生成 `AGENT.md` 草稿。草稿**仅供预览**——在用户确认并保存（`POST /api/v1/agents/{name}/files`）之前，既不落盘也不注册。

## 动态 Provider 与通知渠道注册表

Provider 和通知渠道不再是静态配置——两者都存储在 SQLite 中，通过 CRUD 端点管理。

- **Provider**（`providers` 表）— `ProviderApiController` 暴露创建/列表/查询/更新/删除。运行时引擎按 Provider 名称动态解析 `ChatModel`；模型按 `(name | apiKey | baseUrl)` 构建并缓存，因此修改 key 或 base URL 会在下次调用时重建模型。`oryxos.providers[]` 配置在首次启动时**种入**该表，之后以数据库为准。宪法原则三（显式的 `provider name → ChatModel` 映射）保持不变——只是这张映射表变得可在运行时修改。`mock` Provider 是内置的假模型，无需 key 或 URL。
- **通知渠道**（`notify_channels` 表）— `NotifyChannelApiController` 暴露同样的 CRUD。渠道有一个 `type`（`feishu` | `wecom` | `dingtalk` | `webhook`）和一个 URL。`notify` 工具**按名称**引用渠道（在 `AGENT.md` 正文里，例如「发到 team-lark」）；工具把已注册的渠道解析成对应的适配器和 URL。frontmatter 里不再有 `notify_channels` 字段。

## 可配置的工作区根目录

工作区目录默认为 `.oryxos`，但可配置：

- `application.yml` 中的 `oryxos.root` — 影响 Spring 启动的命令（`serve` / `gateway`）。
- `ORYXOS_ROOT` 环境变量或 `-Doryxos.root=` — 影响不启动 Spring 的轻量 CLI 命令（`init` / `status` / `profile`），并通过 Spring 宽松绑定同样影响已启动的命令。

配置的根目录在运行时会自动加入文件沙箱白名单，因此更改根目录不会破坏文件工具。

## REST API 一览

所有端点都在 `/api/v1` 下，使用 JSON，且（核心阶段假设内网）无需认证。**每一个**响应都包裹在统一信封中：

```json
{ "code": 0, "message": "success", "data": {}, "timestamp": 1720000000000 }
```

`code` 为 0 表示成功；错误携带非零 code 和 message（输入非法 400，资源缺失 404）。整个接口面由十个 Controller 组成：

| Controller | 职责 |
| --- | --- |
| `SystemApiController` | `GET /health`、`GET /info`（运行信息 + 已配置 Provider） |
| `AgentApiController` | Agent CRUD、`invoke`、每个 Agent 的 `memory` / `session`、`generate-files`、`files` |
| `ProviderApiController` | Provider CRUD（SQLite 存储） |
| `NotifyChannelApiController` | 通知渠道 CRUD（SQLite 存储） |
| `SessionApiController` | 会话创建/列表/查询/归档；`messages` 同步运行 ReAct 循环 |
| `ScheduleApiController` | 列出定时任务、执行历史、立即运行、启用/禁用 |
| `ProfileApiController` | 列出派生的 Profile（每个 Agent 目录一个） |
| `ToolApiController` | 列出已注册工具（内置 + MCP） |
| `SandboxWhitelistController` | 运行时管理 `FILE` / `SHELL` / `HTTP` 白名单 |
| `WorkspaceApiController` | 只读工作区目录树；带路径穿越防护的文件读/写 |

## Web 管理台

一个 Vue 3 + Vite 单页应用，通过 `/admin/` 提供，视觉风格与官网一致。侧边栏分组：**概览** / **Agent 列表** / **定时任务** / **Skill 列表**（占位） / **知识库**（占位） / 以及 **OS 运行时** 分组下的 **会话列表**、**Provider 列表**、**Tool 列表**、**Notify 渠道**、**SandBox 列表**。

- Agent 列表带描述列，每行有 **立即触发**（经 console 会话）、**详情**、**删除** 操作；详情视图有 基本信息 / 生成 / 文件 / 会话 / 记忆 标签页，包含一句话「生成」流程（先预览再保存）。记忆标签页展示两张表——核心记忆和归档记忆。
- Provider 与 Notify 渠道页面提供完整 CRUD；Provider 的 api-key 按设计以明文展示。
- 新增/编辑表单均为弹窗对话框，每个列表都有刷新按钮。工作区文件浏览器暴露磁盘上的 Agent 目录树。

## 模块结构

| 模块 | 职责 |
| --- | --- |
| `oryxos-core` | 核心抽象与接口：`OryxTool`、`Session`、`Profile`、`ContextLoader`、`ReActLoop`、`PromptBuilder`、`ToolExecutor`、`AgentService`、`AgentLoader` |
| `oryxos-provider` | LLM 路由：`ProviderService`、Function Calling 格式适配、由 Provider 注册表支撑的动态 `provider name → ChatModel` 解析 |
| `oryxos-memory` | 记忆系统：`MemoryService` 门面、`LongTermMemory`（读写每个 Agent 的 `MEMORY.md`）、`MemoryTools`（`save_memory`、`recall_memory`） |
| `oryxos-tool` | 工具体系：内置工具（`FileTools`、`ShellTools`、`HttpTools`、`NotifyTools`）、`McpClientService`、`McpToolAdapter`、`ToolRegistry`、`SandboxChecker` |
| `oryxos-channel-cli` | CLI 渠道：`CliChannel`、`oryxos chat` 命令实现 |
| `oryxos-web` | REST API：十个 `ApiController`、`GlobalExceptionHandler`、统一响应信封、OpenAPI 规范，以及通过 `/admin/` 提供的 Vue 管理台 |
| `oryxos-storage` | 持久化：SQLite via Spring Data JPA——会话、工具调用、LLM 调用、定时任务、任务执行、记忆条目、通知渠道、Provider 等仓库 |
| `oryxos-cli` | CLI 入口：Picocli 主入口、12 个子命令、`ConfigLoader`（凭证和配置校验） |
| `oryxos-boot` | Spring Boot 启动模块：主类、自动配置、依赖聚合 |

模块之间通过接口通信。新增 Channel 或 Tool 实现只需新建模块，`oryxos-core` 不受影响。

## 关键技术决策

| # | 决策 | 选择 | 理由 |
| --- | --- | --- | --- |
| 1 | ReAct Loop 实现方式 | 自实现；不使用 Spring AI 的 Agent 抽象 | 完全掌控循环行为；避免工具被隐式执行两次 |
| 2 | Spring AI 使用边界 | 仅用于协议转换和 `@Tool` JSON Schema 生成；显式禁用自动工具执行 | Spring AI 的自动执行会让工具跑两遍——Spring AI 一次，`ToolExecutor` 一次 |
| 3 | 执行模型 | 同步阻塞 + Java 21 虚拟线程 | 代码直观，不引入响应式编程复杂度，同时具备高并发能力 |
| 4 | Provider 解析 | 由 SQLite 注册表支撑的动态 `provider name → ChatModel` 映射，按 `(name\|apiKey\|baseUrl)` 缓存 | 显式映射（绝不靠 Bean 类型扫描），但可在运行时修改，Provider 增改无需重启 |
| 5 | HTTP 服务层 | Spring MVC + Java 21 虚拟线程 | 同步风格的代码，单节点支持数千并发请求；扩展阶段可用 `SseEmitter` 支持流式 |
| 6 | 沙箱策略 | 应用层路径/模式白名单，可运行时管理 | `SecurityManager` 从 JDK 17 起废弃、JDK 21 上不可用；完整的容器级沙箱隔离是扩展阶段的工作 |
| 7 | 持久化方案 | SQLite + Spring Data JPA 存关系型数据；每个 Agent 的 `MEMORY.md` 存长期记忆 | 单体部署无需外部数据库进程；审计表从第一天就写入，审计能力不会是事后追加的 |

## 技术栈

| 组件 | 选型 |
| --- | --- |
| 语言 / 运行时 | Java 21（必须；虚拟线程，无 SecurityManager） |
| 框架 | Spring Boot 3.x |
| LLM 集成 | Spring AI + Spring AI Alibaba（仅用于协议转换和 `@Tool` Schema 生成） |
| HTTP 服务 | Spring MVC + Java 21 虚拟线程 |
| 管理台 | Vue 3 + Vite（通过 `/admin/` 提供） |
| 命令行 | Picocli |
| YAML 解析 | SnakeYAML |
| 持久化 | SQLite + Spring Data JPA（`schema.sql` 手动维护；`CREATE TABLE IF NOT EXISTS`，不依赖脆弱的 SQLite `ALTER`） |
| 长期记忆 | 每个 Agent 的 `MEMORY.md` 平文件 + 关键词检索；扩展阶段规划向量检索 |
| MCP 集成 | MCP Java SDK（MCP Client 用于连接外部 MCP server） |
| 日志 | Logback + SLF4J，结构化 JSON 输出 |
| 构建 | Maven 多模块 |
| 未来：原生二进制 | GraalVM Native Image（扩展阶段，启动时间从约 3 秒降至 100ms 以下） |
| 未来：可观测性 | Micrometer + Prometheus（扩展阶段） |
