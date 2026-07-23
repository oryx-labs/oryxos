# OryxOS — Claude Code 项目指南

OryxOS 是用 Java 实现的面向企业场景的 **Distributed AI Agent OS**。装在企业自己的 K8s 或服务器上，作为统一底座运行多个业务 Agent，共享渠道接入、模型路由、工具调用、记忆系统、沙箱执行能力。数据完全留在企业自己的基础设施，不锁任何云生态。

长期目标：走进 Apache 基金会，成为 Apache 顶级项目。

> 详细背景：`docs/DemandAnalysis.md`（需求）、`docs/TechnicalSolution.md`（技术方案）、`docs/IndustryResearch.md`（业界调研）、`docs/AiProgrammingGuide.md`（AI 编程指南）、`docs/oryxos.md`（项目定位）

---

## 技术栈

| 组件 | 选型 |
|------|------|
| 语言 / 运行时 | Java 21（必须，virtual thread 处理并发） |
| 框架 | Spring Boot 3.x |
| LLM 调用 | Spring AI Alibaba（仅用协议转换 + `@Tool` schema 生成） |
| HTTP 服务 | Spring MVC + Java 21 Virtual Thread |
| 命令行 | Picocli |
| YAML 解析 | SnakeYAML |
| 持久化 | SQLite + Spring Data JPA |
| 日志 | Logback + SLF4J（结构化 JSON） |
| 构建 | Maven 多模块 |

---

## 模块结构（9 个）

```
oryxos/
├── oryxos-core          # 核心抽象：OryxTool 接口、Session、Profile、ContextLoader、
│                        #   ReActLoop、PromptBuilder、ToolExecutor、AgentService
├── oryxos-provider      # 能力一：ProviderService、Function Calling 适配、
│                        #   多 Provider 显式映射
├── oryxos-memory        # 能力三：MemoryService 门面、LongTermMemory、
│                        #   MemoryTools（save/recall）
├── oryxos-tool          # 能力四：内置 Tool（文件/Shell/HTTP）、MCP Client、
│                        #   ToolRegistry、SandboxChecker
├── oryxos-channel-cli   # CLI Channel：oryxos chat 实现
├── oryxos-web           # 能力五：WebServer、ApiController、GlobalExceptionHandler、
│                        #   OpenAPI
├── oryxos-storage       # 持久化：SQLite、SessionRepository、
│                        #   ToolInvocationRepository、LlmCallRepository
├── oryxos-cli           # 命令行入口：Picocli 主入口、12 个子命令、ConfigLoader
└── oryxos-boot          # Spring Boot 启动模块：主类、自动配置、依赖聚合
```

模块之间通过接口解耦。新增 Channel 或 Tool 只加新模块，不改 `oryxos-core`。

**模块结构可按需演进**（宪法 v1.1.0）：模块划分跟随 Agent 的能力域，不锁死上面 9 个——可以新建模块（比如把沙箱独立为 `oryxos-sandbox`）或调整模块边界。新建/改名必须在对应特性的 plan 里声明理由，并同步更新本表与 `docs/TechnicalSolution.md` §10。跨模块契约（接口 + 值对象）放 `oryxos-core`，由下游模块实现（依赖倒置），禁止模块间循环依赖。

---

## 不可违背的原则（Constitution）

以下原则来自 `docs/AiProgrammingGuide.md` 和 `docs/TechnicalSolution.md`，所有代码必须遵守。

### 原则一：自实现 ReAct Loop

`ReActLoop` 必须自己实现，**不得**使用 Spring AI 的 Agent 抽象（如 `ChatClient.prompt().call()` 的自动工具执行）。核心循环约数十行 Java，完整掌握 Agent 工作机制，保留未来定制循环行为的空间。

### 原则二：Spring AI 只用两件事 ⚠️

Spring AI 在 OryxOS 里只做：

1. LLM Provider 协议转换（OpenAI / Anthropic / Gemini 等各家格式差异由它吸收）
2. `@Tool` 注解的 JSON Schema 生成

**必须禁用** Spring AI 的自动 tool 执行。Tool 的调度和执行完全由 `ReActLoop` + `ToolExecutor` 控制。违反此原则会导致 tool 被调两次。

```java
// 错误：不得用 Spring AI 自动执行 tool
chatClient.prompt(prompt).tools(tools).call().content();

// 正确：只用 Spring AI 做 LLM 调用，tool 调用结果自己处理
ChatResponse response = chatModel.call(new Prompt(messages, options));
// 然后自己检查 response 里的 tool call，自己执行
```

### 原则三：Provider 必须显式映射

多 Provider 并存时，**不得**靠扫描 Spring 容器里的 `ChatModel` Bean 类型来区分 Provider（因为 Bean 类型相同）。必须维护 `provider name → ChatModel` 的显式映射表：

```java
// 正确：显式映射
Map<String, ChatModel> providerMap = Map.of(
    "deepseek", deepseekChatModel,
    "qwen",     qwenChatModel,
    "kimi",     kimiChatModel
);
```

### 原则四：一个目录 = 一个 Agent；`AGENT.md` 归 `ContextLoader`，不是 Tool

**一个目录 = 一个 Agent**（借 Anthropic Agent Skills 的**目录形态**，但定义的是 Agent）：`.oryxos/agents/<name>/` 里 `AGENT.md` = frontmatter（这个 Agent 自己的 profile：name/description/provider/model/tools/notify_channels/schedules）+ 正文（任务指令）；外加可选 `skills/*.md`（子指令）、`scripts/`（脚本）、`REFERENCE.md`（参考）。`AgentLoader.deriveProfile(agentDir)` 把 frontmatter 派生成底座认识的 `Profile`——`.oryxos/profiles/` 取消，profile 就是 frontmatter。

加载走**一个 Agent 内部的渐进式披露**：`AGENT.md` **正文**由 `ContextLoader`/`PromptBuilder` 注入 system prompt（跟 Bootstrap 文件 `AGENTS.md`/`SOUL.md`/`USER.md` 同一层，因为它就是这个 Agent）；目录里的**参考**用底座既有 `read_file` 按需读、**脚本**用 `shell` 按需跑。**一个 Agent 目录永远不是一个可执行 Tool**，不进 `ToolRegistry`、不放在 `oryxos-tool` 模块里（详见 `docs/TechnicalSolution.md` §11）。

> **修订（v1.2.0，第 32 节）：Skill 升级为全局共享能力库。** 原条款"没有跨 Agent 的能力库 / 没有 `use_skill` / 没有全局能力索引"**已废止**。现在 Skill 是**全局的**：存 `.oryxos/skills/<name>/SKILL.md`（frontmatter `name`/`description` + 正文=约束指令），由 `SkillService`/`SkillStore`/`SkillRegistry`（`oryxos-core`，与 Agent 那套同构）做 CRUD，`/api/v1/skills` 暴露。Agent 在 `AGENT.md` frontmatter 用 `skills: [名]` **按名引用**全局 Skill，`ContextLoader` 组装 system prompt 时把引用到的 Skill 正文**注入**（强约束产出，不靠模型自觉 `read_file`）；引用不存在的 Skill 记 WARN 跳过。生成 Agent 时作者模型会拿到 Skill 目录（名+描述）自动按需选，用户也可在前端显式勾选必启用的 Skill。**不变的边界**：Skill 仍不是可执行 Tool、不进 `ToolRegistry`；Skill 的加载/注入仍归 `oryxos-core` 的 `ContextLoader`（不进 `oryxos-tool`）。每个 Agent 目录里的 `skills/` 子目录不再是脚手架默认项——约束产出的规范优先用全局 Skill 库。

### 原则五：审计表 Day One 写入

`tool_invocations` 和 `llm_calls` 两张审计表**核心阶段就必须写入**（不需要查询接口，但写入不能省）。不得以"日志够了"为由跳过落库，可审计是 OryxOS 的核心差异化能力。

### 原则六：不使用 Java SecurityManager

`SecurityManager` 在 JDK 17 起废弃、JDK 21 已不可用。Sandbox 通过 `SandboxChecker` 的 Path / Pattern 白名单实现：
- 文件操作：路径白名单（`file.allowed_paths`）
- Shell：命令首 token 白名单（`shell.allowed_commands`）
- HTTP：域名通配符白名单（`http.allowed_domains`）

### 原则七：同步执行模型

核心阶段全程同步阻塞，配合 Java 21 Virtual Thread 处理并发。**不引入** Reactor / WebFlux / CompletableFuture 等异步编程模型（SSE 流式响应放扩展阶段）。

### 原则八：Tool 模块三合一

内置 Tool、MCP Client 合并在一个 `oryxos-tool` 模块，**不拆成多个模块**。`AGENT.md`（及 Agent 目录里的子指令）加载归 `oryxos-core` 的 `ContextLoader`。

---

## 工作区结构（运行时）

OryxOS 启动后在当前目录创建 `.oryxos/` 工作区：

```
.oryxos/
├── agents/             # 每个子目录 = 一个 Agent（AGENT.md + 可选 scripts/ REFERENCE.md）
├── skills/             # 全局 Skill 库：每个子目录 = 一个 Skill（SKILL.md），Agent 按名引用来约束产出（第 32 节）
├── memory/
│   └── MEMORY.md       # 长期记忆（Agent 通过 save_memory 写入，不得手动修改）
├── sessions/           # 会话数据（已迁入 SQLite，此目录备用）
├── logs/               # 结构化日志
├── mcp_servers.yaml    # MCP server 配置
├── oryxos.db           # SQLite 数据库
├── AGENTS.md           # Bootstrap：项目级 agent 行为说明
├── SOUL.md             # Bootstrap：agent 人格定义
└── USER.md             # Bootstrap：用户偏好（只读，agent 不写）
```

**`MEMORY.md` vs `USER.md` 区别**：
- `USER.md`：用户手写的初始设定，OryxOS 只读不写
- `MEMORY.md`：Agent 通过 `save_memory` Tool 写入的成长记录，OryxOS 读写

---

## 核心数据模型

### AGENT.md（`.oryxos/agents/<name>/AGENT.md`）

一个 Agent 目录里 `AGENT.md` = frontmatter（这个 Agent 自己的 profile）+ 正文（任务指令）。`AgentLoader.deriveProfile(agentDir)` 把 frontmatter 派生成底座认识的 `Profile`。子指令 / 脚本放同目录的 `skills/`、`scripts/`，由正文指引用 `read_file`/`shell` 按需取用（不在 frontmatter 里声明）。

```markdown
---
name: ops-agent
description: 运维助手
identity:
  agent_name: 运维小欧
  prompt: 你是一个专业的运维助手...
provider:
  name: deepseek          # 对应 ProviderService 里的显式映射 key
  model: deepseek-chat
  temperature: 0.7
  api_key: ${DEEPSEEK_API_KEY}   # 从环境变量读取，不明文写死
tools:
  - read_file
  - shell
  - http_get
  - save_memory
  - recall_memory
mcp_servers:
  - github-mcp
channels:
  - name: cli
bootstrap:
  - AGENTS.md
  - SOUL.md
  - USER.md
settings:
  max_iterations: 10
  max_history_turns: 20
---

你是一个专业的运维助手。被触发时……（Agent 的任务指令正文，注入 system prompt）
```

### SQLite 核心表

**sessions**

| 字段 | 类型 | 说明 |
|------|------|------|
| `session_id` | VARCHAR PK | channel+user+profile 联合生成 |
| `profile_name` | VARCHAR | 关联 Profile |
| `channel` | VARCHAR | 接入渠道 |
| `user_id` | VARCHAR | 用户标识 |
| `messages_json` | TEXT | JSON 序列化的对话历史 |
| `status` | VARCHAR | `active` / `archived` |
| `created_at` | TIMESTAMP | 创建时间 |
| `last_active_at` | TIMESTAMP | 最后活跃时间 |
| `archived_at` | TIMESTAMP | 归档时间（可空） |

**tool_invocations**（审计，day one 写入）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT PK | 主键 |
| `session_id` | VARCHAR | 关联 Session |
| `tool_name` | VARCHAR | Tool 名称 |
| `input_json` | TEXT | 调用参数 |
| `result_json` | TEXT | 执行结果 |
| `success` | BOOLEAN | 是否成功 |
| `error_message` | TEXT | 错误信息（可空） |
| `duration_ms` | BIGINT | 执行耗时 |
| `created_at` | TIMESTAMP | 调用时间 |

**llm_calls**（审计，day one 写入）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT PK | 主键 |
| `session_id` | VARCHAR | 关联 Session |
| `provider` | VARCHAR | Provider 名称 |
| `model` | VARCHAR | 模型名 |
| `prompt_tokens` | INT | 输入 token 数 |
| `completion_tokens` | INT | 输出 token 数 |
| `total_tokens` | INT | 总 token 数 |
| `duration_ms` | BIGINT | 调用耗时 |
| `created_at` | TIMESTAMP | 调用时间 |

> **SQLite 迁移注意**：`hibernate.ddl-auto=update` 在 SQLite 上 `ALTER TABLE` 支持很弱。表结构变更时**不要**依赖 Hibernate 自动迁移，手动维护建表脚本或引入 Flyway。

---

## ReAct Loop 工作机制

```
用户消息
  → 追加到 Session 对话历史
  → PromptBuilder 组装 Prompt：
      [1] system prompt（AGENT.md 正文 + Bootstrap；子指令/脚本经 read_file/shell 按需取）← ContextLoader
      [2] 长期记忆（MEMORY.md 全文，超 4000 字自动截断）         ← MemoryService
      [3] 对话历史（最近 max_history_turns 轮）                  ← SessionManager
      [4] 可用 Tool 列表（Function Calling 格式）                ← ToolRegistry
  → ProviderService 调 LLM（写 llm_calls 表）
  → [无 Tool 调用] → 返回最终响应
  → [有 Tool 调用] → ToolExecutor 执行 Tool
      → SandboxChecker 白名单校验
      → 执行（内置 Tool 在进程内 / MCP Tool 通过 JSON-RPC 转发）
      → 写 tool_invocations 表
      → 结果追加到对话历史
  → 回到组装 Prompt 继续循环（最多 max_iterations 次，默认 10）
```

---

## Tool 体系

### OryxTool 接口（所有 Tool 的统一抽象）

```java
interface OryxTool {
    String getName();
    String getDescription();
    JsonSchema getInputSchema();
    ToolResult execute(JsonNode input);
}
```

`ToolResult` 包含：`success`、`content`、`errorMessage`、`retryable`。

### 内置 Tool（核心阶段 9 个）

| Tool | 类 | 说明 |
|------|-----|------|
| `read_file` | `FileTools` | 读文件，路径白名单 |
| `write_file` | `FileTools` | 写文件，路径白名单 |
| `list_dir` | `FileTools` | 列目录，路径白名单 |
| `shell` | `ShellTools` | 执行 bash，命令白名单 + 超时 |
| `http_get` | `HttpTools` | GET 请求，域名白名单 |
| `http_post` | `HttpTools` | POST 请求，域名白名单 |
| `save_memory` | `MemoryTools` | 追加到 MEMORY.md |
| `recall_memory` | `MemoryTools` | 关键词检索 MEMORY.md |
| `notify` | `NotifyTools` | 推送到 Profile 的 `notify_channels`，核心阶段走 `WebhookNotifyAdapter` |

### Plugin Tool 三档

| 方式 | 门槛 | 推荐 | 实现 |
|------|------|------|------|
| 零代码 | 最低 | ⭐ 主推 | 写 SKILL.md + 复用社区 MCP server |
| 轻代码 | 中 | ⭐⭐ | 任意语言写 MCP server，配置在 `mcp_servers.yaml` |
| 重代码 | 高 | ⭐⭐⭐ | Java `@Tool` 注解 Spring Bean，进程内直接调用 |

> 选择原则：能用方式一就不用方式二，能用方式二就不用方式三。

---

## Web Service API

核心阶段 10 个端点，统一前缀 `/api/v1`：

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/sessions` | 创建会话 |
| `POST` | `/sessions/{id}/messages` | 发消息（触发 ReAct Loop） |
| `GET` | `/sessions/{id}` | 查会话历史 |
| `DELETE` | `/sessions/{id}` | 归档会话 |
| `POST` | `/agents/{name}/invoke` | 无状态调用 Agent |
| `GET` | `/profiles` | 列所有 Profile |
| `GET` | `/memory` | 查长期记忆（MEMORY.md） |
| `GET` | `/tools` | 列可用 Tool |
| `GET` | `/health` | 健康检查 |
| `GET` | `/info` | 运行信息 + Provider 状态 |

**核心阶段不做**：认证（假设内网）、SSE 流式、WebSocket、限流、RBAC。

---

## 命令行工具（12 个）

```bash
# 启动和状态
oryxos init                      # 初始化 .oryxos/ 工作区
oryxos status                    # 查看配置和运行状态
oryxos chat [--profile <name>]   # 交互式多轮对话（默认 profile: default）
oryxos serve [--port 8080]       # 启动 HTTP API 服务
oryxos gateway                   # 守护进程模式（多 Channel）

# Profile 管理
oryxos profile list
oryxos profile create <name>
oryxos profile show <name>
oryxos profile delete <name>

# 查询
oryxos provider list
oryxos tool list
oryxos session list
```

---

## 配置加载规则

敏感配置（API key、MCP server 凭证）通过环境变量注入，**不得**明文写在 Profile YAML 里：

```yaml
provider:
  name: deepseek
  api_key: ${DEEPSEEK_API_KEY}   # 从环境变量读取
```

`ConfigLoader` 启动时做必填项和格式校验，缺失或非法时给清晰报错，不静默失败。

---

## 五大核心能力与验收 Demo

| 能力 | 核心组件 | 验收 Demo |
|------|---------|---------|
| **一：对接 LLM** | `ProviderService`，显式 provider 映射 | — |
| **二：ReAct 循环** | `ReActLoop`、`PromptBuilder`、`ToolExecutor` | Demo 一：`oryxos chat` 查天气穿衣 |
| **三：Memory** | `MemoryService`、`LongTermMemory`、`MEMORY.md` | Demo 二：跨对话记偏好 |
| **四：Plugin Tool** | `ToolRegistry`、`SandboxChecker`、MCP Client | Demo 三：零代码 PR digest |
| **五：Web Service** | `WebServer`、`ApiController` × 6 | Demo 四+五：REST 端点联动 |

---

## 四周实施节奏

| 周次 | 核心任务 | 涉及模块 | 验收 Demo |
|------|---------|---------|----------|
| 第一周 | Provider 抽象 + ReAct Loop | `oryxos-core` `oryxos-provider` `oryxos-channel-cli` `oryxos-cli` | `oryxos chat`：查天气穿衣 |
| 第二周 | Memory + Tool 体系 | `oryxos-memory` `oryxos-tool` | Agent 跨对话记偏好；零代码 PR digest |
| 第三周 | Web Service | `oryxos-web` `oryxos-storage` | 10 个 REST 端点完整调用 |
| 第四周 | 多 Agent 演示 + 工程化 | 所有模块收尾 | 多 Agent 并存；Session 跨重启恢复；项目主页 |

---

## 常见陷阱

| 陷阱 | 症状 | 修复 |
|------|------|------|
| Spring AI 自动执行 tool | Tool 被调两次，结果重复 | 禁用 `ChatClient` 的自动 tool 执行，由 `ToolExecutor` 接管 |
| Provider 靠类型扫描区分 | 多 Provider 时路由错乱 | 改用显式 `Map<String, ChatModel>` 映射 |
| `AGENT.md` / 子指令放进 Tool 模块 | Agent 目录被当 Tool 注册，执行时报错 | 归 `ContextLoader`：正文注入 system prompt，子指令/脚本经 read_file/shell 按需取 |
| 审计表只写日志不落库 | 扩展阶段审计功能需要反解析日志 | `tool_invocations` + `llm_calls` 核心阶段就写入 SQLite |
| 用 `hibernate.ddl-auto=update` 迁移表结构 | SQLite ALTER TABLE 报错 | 手动维护建表脚本或引入 Flyway |
| 在 ReAct Loop 里用异步 | 复杂度激增，Virtual Thread 优势消失 | 保持同步阻塞，Virtual Thread 自动处理 IO 等待 |
| `MEMORY.md` 超过 4000 字不截断 | 注入 system prompt 超 context window | `LongTermMemory.truncateIfNeeded()` 超阈值保留最近内容 |
| Tool 模块拆成多个 | 模块间依赖混乱 | 内置 Tool + MCP Client 合并为一个 `oryxos-tool` 模块 |

---

## 设计原则

- **底座优先于 Agent**：最重要的交付不是某个强大的 Agent，而是让任意 Agent 可靠运行的环境
- **自实现核心，复用管道**：ReAct 循环手写；LLM 协议适配委托给 Spring AI Alibaba
- **一个目录 = 一个 Agent**：一个业务 Agent 由一个目录定义——`AGENT.md`（frontmatter 配置：谁/何时/怎么跑 + 正文指令），可选 `skills/` 子指令、`scripts/` 脚本；子资源经底座 `read_file`/`shell` 按需取用，不需要写 Java 代码
- **对接开放标准**：工具用 MCP，Agent 间协作用 A2A，Agent 目录借 Anthropic Agent Skills 的形态（目录 + 渐进式披露）
- **无状态实例，状态外置**：这是未来走向分布式架构而不需要大改设计的前提
- **安全是地基，不是补丁**：工具来源管控、最小权限、强制沙箱白名单、凭证走环境变量、完整审计记录从第一天就写入 SQLite
- **分阶段克制**：先构建最小完整的运行时内核；治理和分布式基础设施在真实使用数据验证后再做
