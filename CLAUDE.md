# OryxOS — Claude Code 项目指南

## 项目定位

OryxOS 是用 Java 实现的面向企业场景的 **AI Agent OS**。它装在企业自己的 K8s 或服务器上，作为统一底座运行多个业务 Agent（运维助手、客服助手、HR 助手等），共享渠道接入、模型路由、工具调用、记忆系统、沙箱执行能力。数据完全留在企业自己的基础设施，不锁任何云生态。

**长期愿景**：走进 Apache 基金会，成为 Apache 顶级项目。

### 为什么需要 OryxOS

**业界现状**：OpenClaw（Node.js）和 Hermes Agent（Python）已验证 Agent OS 设计，但 Java 生态在这一层是空白。Java/Spring 体系的企业要用 Agent OS，今天只能跨技术栈写大量胶水代码。

**核心空白**：
- Java 生态没有任何项目把 "Agent OS" 作为定位
- 严监管企业（银行、政府、电信、能源、医疗）需要私有部署、完全可审计、跟现有 Java 体系对齐的 Agent 底座
- OpenClaw 和 Hermes 的企业级治理（多租户 RBAC、SSO、完整审计）仍是空白

**OryxOS 填补的位置**：Java 原生的、企业能完全掌控的、私有可审计的 Agent 统一底座。

### 交付分两段

理解这个分层，才能看懂 OryxOS 的交付节奏：

1. **核心阶段（4 周 12 小时）**：Agent OS 的运行时内核，能力上对齐业界开源 Agent OS 的基础层
2. **扩展阶段**：真正的差异化治理层（多租户、SSO、完整审计、Tool 治理），由扩展阶段和社区共建陆续补齐

**核心阶段交付的是地基，企业级治理是终局。**

> 详细背景：`docs/DemandAnalysis.md`（需求）、`docs/TechnicalSolution.md`（技术方案）、`docs/IndustryResearch.md`（业界调研）、`docs/AiProgrammingGuide.md`（AI 编程指南）

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

以下原则来自 `docs/AiProgrammingGuide.md` 和 `docs/TechnicalSolution.md`，所有代码必须遵守。**这些是 AI 编程最容易出错的地方，必须特别注意。**

### 原则一：自实现 ReAct Loop

`ReActLoop` 必须自己实现，**不得**使用 Spring AI 的 Agent 抽象（如 `ChatClient.prompt().call()` 的自动工具执行）。

**理由**：核心循环约数十行 Java，完整掌握 Agent 工作机制，保留未来定制循环行为的空间。这是 OryxOS 作为"OS"而非"框架包装"的关键体现。

### 原则二：Spring AI 只用两件事 ⚠️ **最容易被违反**

Spring AI 在 OryxOS 里**只做两件事**：

1. LLM Provider 协议转换（OpenAI / Anthropic / Gemini 等各家格式差异由它吸收）
2. `@Tool` 注解的 JSON Schema 生成

**必须禁用** Spring AI 的自动 tool 执行。Tool 的调度和执行完全由 `ReActLoop` + `ToolExecutor` 控制。

**⚠️ 为什么这条最容易被违反**：Spring AI 的文档和示例代码大量展示自动 tool 执行，AI 编程助手容易顺手写出来。违反此原则会导致 tool 被调两次。

```java
// ❌ 错误：不得用 Spring AI 自动执行 tool
chatClient.prompt(prompt).tools(tools).call().content();

// ✅ 正确：只用 Spring AI 做 LLM 调用，tool 调用结果自己处理
ChatResponse response = chatModel.call(new Prompt(messages, options));
// 然后自己检查 response 里的 tool call，自己执行
```

**检查方法**：搜索代码里所有 `chatClient` 或 `ChatClient` 的调用，确保没有 `.tools()` 链式调用。

### 原则三：Provider 必须显式映射

多 Provider 并存时，**不得**靠扫描 Spring 容器里的 `ChatModel` Bean 类型来区分 Provider（因为 Bean 类型相同）。必须维护 `provider name → ChatModel` 的显式映射表：

```java
// ✅ 正确：显式映射
Map<String, ChatModel> providerMap = Map.of(
    "deepseek", deepseekChatModel,
    "qwen",     qwenChatModel,
    "kimi",     kimiChatModel
);
```

**为什么重要**：多 Provider 场景下，类型扫描会导致路由错乱，企业多模型切换会失败。

### 原则四：一个目录 = 一个 Agent；Skill 以本地软连接绑定并渐进披露

**一个目录 = 一个 Agent**：`.oryxos/agents/<name>/` 里 `AGENT.md` = frontmatter（运行配置）+ 正文（任务指令），外加可选 `skills/`（Skill 绑定视图）、`scripts/`、`REFERENCE.md`。`AgentLoader.deriveProfile(agentDir)` 把 frontmatter 派生成底座认识的 `Profile`。

**关键点**：
- 公共 Skill 实体统一存放在 `.oryxos/skills/<name>/`
- Agent 可见的 Skill 只由 `.oryxos/agents/<agent>/skills/<name>` 下指向公共实体的**相对软连接**表达
- 软连接集合是唯一绑定真相源，`AGENT.md` frontmatter 不再声明 `skills:`

**三层渐进式披露**：
1. L1：每轮 prompt 只注入当前 Agent 已绑定 Skill 的 `name + description + 本地绝对读取路径`
2. L2：模型命中后用 `read_file` 读取 `SKILL.md` 正文
3. L3：Skill 附属参考/脚本继续按需读取或运行

**禁止事项**：不得预载正文、不得新增 `use_skill`、Skill 不进 `ToolRegistry`。

**CRUD 要求**：启动恢复必须检测 dangling/escaped/invalid-target/name-mismatch/stale-reference；公共 Skill 被引用时默认拒绝删除并返回引用 Agent。

### 原则五：审计表 Day One 写入

`tool_invocations` 和 `llm_calls` 两张审计表**核心阶段就必须写入**（不需要查询接口，但写入不能省）。

**理由**：可审计是 OryxOS 的核心差异化能力，审计数据的地基应该 day one 就立起来。不得以"日志够了"为由跳过落库，纯靠日志后期要做审计还得反解析返工。

### 原则六：不使用 Java SecurityManager；软连接必须校验真实路径

`SecurityManager` 在 JDK 17 起废弃、JDK 21 已不可用。Sandbox 通过 `SandboxChecker` 的 Path / Pattern 白名单实现：
- 文件操作：路径白名单（`file.allowed_paths`）
- Shell：命令首 token 白名单（`shell.allowed_commands`）
- HTTP：域名通配符白名单（`http.allowed_domains`）

**安全要求**：
- 文件目标存在时必须用 `toRealPath()` 校验真实路径仍位于白名单根
- 新建路径校验最近存在父目录的真实路径
- Agent Skill 绑定只允许指向 `.oryxos/skills/` 的相对软连接，拒绝绝对链接和越界链接

### 原则七：同步执行模型

核心阶段全程同步阻塞，配合 Java 21 Virtual Thread 处理并发。**不引入** Reactor / WebFlux / CompletableFuture 等异步编程模型（SSE 流式响应放扩展阶段）。

**理由**：同步代码简单直观，Virtual Thread 自动处理 IO 等待，无需响应式编程的复杂度。

### 原则八：Tool 模块三合一

内置 Tool、MCP Client 合并在一个 `oryxos-tool` 模块，**不拆成多个模块**。`AGENT.md`（及 Agent 目录里的子指令）加载归 `oryxos-core` 的 `ContextLoader`。

**理由**：它们共享同一个 `OryxTool` 抽象和 `ToolRegistry`，耦合度高，核心阶段没必要拆细。

---

## 工作区结构（运行时）

OryxOS 启动后在当前目录创建 `.oryxos/` 工作区：

```
.oryxos/
├── agents/             # 每个子目录 = 一个 Agent（AGENT.md + skills/软连接 + scripts/ REFERENCE.md）
├── skills/             # 公共 Skill 实体库：每个子目录 = 一个 Skill（SKILL.md + 可选附属资源）
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

一个 Agent 目录里 `AGENT.md` = frontmatter（这个 Agent 自己的 profile）+ 正文（任务指令）。`AgentLoader.deriveProfile(agentDir)` 把 frontmatter 派生成底座认识的 `Profile`。该 Agent 的 `skills/` 只放指向公共 Skill 实体的相对软连接；`ContextLoader` 每轮注入绑定 Skill 的名称、描述和读取路径，正文与附属资源经 `read_file`/`shell` 按需取用。

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
      [1] system prompt（AGENT.md 正文 + Skill 元数据 + Bootstrap；正文/脚本按需取）← ContextLoader
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

## 附录：快速参考

### 关键文件位置

```
项目根目录/
├── CLAUDE.md                          # 本文档（项目指南）
├── docs/
│   ├── DemandAnalysis.md              # 需求文档
│   ├── TechnicalSolution.md           # 技术方案
│   ├── IndustryResearch.md            # 业界调研
│   └── AiProgrammingGuide.md          # AI 编程指南
└── .oryxos/                           # 工作区（运行时创建）
    ├── agents/                        # Agent 目录
    │   └── <name>/
    │       ├── AGENT.md               # Agent 定义
    │       ├── skills/                # Skill 软连接
    │       ├── scripts/               # 脚本
    │       └── REFERENCE.md           # 参考资料
    ├── skills/                        # 公共 Skill 实体库
    │   └── <name>/
    │       ├── SKILL.md               # Skill 定义
    │       └── ...                    # 附属资源
    ├── memory/
    │   └── MEMORY.md                  # 长期记忆
    ├── sessions/                      # 会话历史
    ├── logs/                          # 日志
    ├── mcp_servers.yaml               # MCP 配置
    ├── oryxos.db                      # SQLite 数据库
    ├── AGENTS.md                      # Bootstrap
    ├── SOUL.md                        # Bootstrap
    └── USER.md                        # Bootstrap
```

### Constitution 快速检查清单

实施过程中每个 Task 完成后都过一遍：

- [ ] **原则一**：ReAct Loop 是自己实现的，没有用 Spring AI 的 Agent 抽象
- [ ] **原则二**：没有 Spring AI 自动 tool 执行（搜索 `.tools()` 链式调用）
- [ ] **原则三**：Provider 是显式映射（`Map<String, ChatModel>`），不是类型扫描
- [ ] **原则四**：Agent 定义在 `.oryxos/agents/<name>/` 目录，Skill 通过软连接绑定
- [ ] **原则五**：`tool_invocations` 和 `llm_calls` 都有写入（成功和失败都记录）
- [ ] **原则六**：没有用 `SecurityManager`，文件操作用 `toRealPath()` 校验
- [ ] **原则七**：没有异步编程（没有 `CompletableFuture`、`Reactor`、`WebFlux`）
- [ ] **原则八**：Tool 相关都在 `oryxos-tool` 一个模块

### 核心概念速查

| 术语 | 简短定义 |
|------|---------|
| **Agent OS** | 运行和管理 AI Agent 的底座系统 |
| **Agent** | 一个目录（`AGENT.md` + 可选 `skills/`、`scripts/`） |
| **Profile** | 底座内部的运行时配置对象，由 `AGENT.md` frontmatter 派生 |
| **ReAct** | Reason + Act，Agent 核心工作机制 |
| **Tool** | Agent 可调用的外部能力（内置 + Plugin） |
| **Memory** | 三层：会话记忆、长期记忆（MEMORY.md）、情景记忆（扩展阶段） |
| **Skill** | 公共能力模板，存 `.oryxos/skills/`，Agent 通过软连接绑定 |
| **Provider** | LLM API 服务的统一抽象 |
| **Channel** | Agent 对外接入的消息入口（CLI、企业微信、飞书等） |
| **Sandbox** | 工具执行的隔离环境（核心阶段：应用层白名单） |

### 常用命令速查

```bash
# 初始化和状态
oryxos init                      # 初始化工作区
oryxos status                    # 查看状态
oryxos chat [--profile <name>]   # 交互式对话
oryxos serve [--port 8080]       # 启动 HTTP API
oryxos gateway                   # 守护进程模式

# Agent 管理
oryxos profile list              # 列出所有 Agent
oryxos profile create <name>     # 创建 Agent
oryxos profile show <name>       # 查看 Agent
oryxos profile delete <name>     # 删除 Agent

# 查询
oryxos provider list             # 列出 Provider
oryxos tool list                 # 列出 Tool
oryxos session list              # 列出会话
```

### 关键技术决策速查

| 决策点 | 选择 | 理由 |
|--------|------|------|
| JDK 版本 | 21+ | Virtual Thread 处理并发 |
| 框架 | Spring Boot 3.x | 企业后端事实标准 |
| LLM 调用 | Spring AI Alibaba | 复用主流 LLM connector |
| 执行模型 | 同步阻塞 + Virtual Thread | 简单直观，高并发 |
| ReAct 实现 | 自己实现 | 完全可控 |
| 持久化 | SQLite + JPA | 单二进制部署 |
| Sandbox | 应用层白名单（核心阶段） | JDK 21 不可用 SecurityManager |
| 模块数量 | 9 个 Maven 模块 | 按能力域划分 |

### 核心阶段不做的事（放扩展阶段）

- 多 Channel 接入（企业微信、飞书、钉钉等 IM）
- Provider Fallback 和 Hedge Racing
- Memory 自动抽取和语义检索
- 情景记忆（Memory 第三层）
- Tool Policy（Profile 级别的 allow/deny）
- 完整 Sandbox（容器/microVM 隔离）
- Web Service 剩余端点（Agent CRUD、Memory 操作、审计查询）
- 认证机制（API Key、JWT）
- SSE 流式响应
- WebSocket
- RBAC 权限
- 限流
- 多租户
- SSO 集成
- Web 管理台
- 集群化部署

### 性能目标

| 指标 | 目标 |
|------|------|
| 单节点 Agent 数 | ≥ 10 个 |
| 单节点并发 Session 数 | ≥ 100 个 |
| Session 创建 P99 延迟 | ≤ 200ms |
| OryxOS 内部转发开销 | ≤ 50ms |

---

## 总结

OryxOS 是 Java 生态的第一个 Agent OS，填补 Java 在这一层的空白。核心阶段 4 周 12 小时交付运行时内核，能力上对齐业界开源 Agent OS 基础层；扩展阶段补齐企业级治理（多租户、SSO、完整审计、Tool 治理）。

**核心理念**：
- 底座优先于 Agent——最重要的交付是让任意 Agent 可靠运行的环境
- 自实现核心，复用管道——ReAct 循环手写，LLM 协议适配委托给 Spring AI Alibaba
- 一个目录 = 一个 Agent——配置出来，不是写代码写出来
- 安全是地基，不是补丁——审计、沙箱、凭证管理从第一天就做对

**AI 编程要点**：
- Constitution 八条原则不可违背，特别是"Spring AI 只用两件事"
- 实施过程中频繁检查是否跑偏，发现偏离立刻修正
- 每周有明确的验收标准，不通过不进下一周
- 最终验收是两个 Demo 完整跑通

**长期愿景**：走进 Apache 基金会，成为 Apache 顶级项目。

## 五大核心能力与验收标准

OryxOS 核心阶段围绕五大核心能力展开，每个能力都有明确的验收标准。

| 能力 | 核心组件 | 验收 Demo | 优先级 |
|------|---------|---------|--------|
| **一：对接 LLM** | `ProviderService`，显式 provider 映射 | — | P0 |
| **二：ReAct 循环** | `ReActLoop`、`PromptBuilder`、`ToolExecutor` | Demo 一：每日天气 | P0 |
| **三：Memory** | `MemoryService`、`LongTermMemory`、`MEMORY.md` | Demo 二：每日科技日报 | P0 |
| **四：Plugin Tool** | `ToolRegistry`、`SandboxChecker`、MCP Client | Demo 二：每日科技日报 | P0 |
| **五：Web Service** | `WebServer`、`ApiController` × 6 | — | P0 |

### 能力详解

#### 能力一：对接 LLM

**核心价值**：Agent 不感知具体调的是哪家模型，运行时切换无 lock-in。

**基于这个能力可以做的事**：
- 任意业务场景的自然语言对话助手
- 同一个 Agent 在不同任务用不同模型（简单任务走便宜模型、复杂任务走强模型）
- 接入企业自有的本地推理服务（Ollama、vLLM），数据完全不出企业
- 多 Provider 编排

#### 能力二：ReAct 循环

**核心价值**：Agent 的大脑，让 Agent 能自主决定何时调用哪个工具。

**基于这个能力可以做的事**：
- 多步骤任务可以一次对话内连续完成
- Agent 出错时能自己回滚、重试、换工具
- 复杂业务流程不需要预先编排，Agent 在运行时动态决定执行路径

#### 能力三：Memory 三层记忆

**核心价值**：Agent 跨对话记住用户偏好、项目背景、历史决策。

| 层次 | 说明 | 核心阶段 |
|------|------|----------|
| 会话记忆 | 当前对话的完整历史 | ✅ 实现 |
| 长期记忆 | 用户偏好、项目背景、关键事实，存在 MEMORY.md | ✅ 实现（极简版） |
| 情景记忆 | 每个任务过程中学到的东西 | ⏳ 扩展阶段 |

#### 能力四：Plugin Tool 体系

**核心价值**：Agent 能调用工具实际操作系统。

**Plugin Tool 三档接入**：

| 方式 | 门槛 | 推荐 | 实现 | 适用场景 |
|------|------|------|------|----------|
| 零代码 | 最低 | ⭐⭐⭐ 主推 | 写 AGENT.md + 复用社区 MCP server | 业务方只描述意图，LLM 自己组合调用 |
| 轻代码 | 中 | ⭐⭐ | 任意语言写 MCP server，配置在 `mcp_servers.yaml` | 接入企业自有系统（ERP、CRM） |
| 重代码 | 高 | ⭐ | Java `@Tool` 注解 Spring Bean，进程内直接调用 | 深度集成，性能最好 |

> 选择原则：能用方式一就不用方式二，能用方式二就不用方式三。

#### 能力五：Web Service

**核心价值**：OryxOS 的对外门面，业务系统通过 REST API 集成。这是 OryxOS 区别于偏个人定位的 OpenClaw、Hermes 的关键能力。

**核心阶段 10 个端点**：

| 类别 | 端点 | 说明 |
|------|------|------|
| 会话管理 | `POST /api/v1/sessions` | 创建会话 |
| 会话管理 | `POST /api/v1/sessions/{id}/messages` | 发消息（触发 ReAct Loop） |
| 会话管理 | `GET /api/v1/sessions/{id}` | 查会话历史 |
| 会话管理 | `DELETE /api/v1/sessions/{id}` | 归档会话 |
| Agent 调用 | `POST /api/v1/agents/{name}/invoke` | 无状态调用 Agent |
| 信息查询 | `GET /api/v1/profiles` | 列所有 Profile |
| 信息查询 | `GET /api/v1/memory` | 查长期记忆（MEMORY.md） |
| 信息查询 | `GET /api/v1/tools` | 列可用 Tool |
| 系统状态 | `GET /api/v1/health` | 健康检查 |
| 系统状态 | `GET /api/v1/info` | 运行信息 + Provider 状态 |

### 验收 Demo（两个端到端场景）

核心阶段完成的标志是两个每日自动运行的端到端 Demo 跑通：

#### Demo 一：每日天气

**验证能力**：能力一+二（LLM + ReAct）、能力四（内置 HTTP Tool + NotifyTools）、定时任务

**场景**：每天早上 8 点，Agent 自动查天气、生成穿搭建议，推送到企业 IM 群，不需要人工发起。

**验收标准**：
- [ ] 不需要人工触发，到点自动跑完整 ReAct 循环
- [ ] 查天气和推送各一次 HTTP 调用，都过 Sandbox 域名白名单
- [ ] 两次调用都写入 `tool_invocations` 审计表
- [ ] `GET /api/v1/sessions/{id}` 能查到这次自动触发的完整对话记录
- [ ] 同一个 Agent 也能通过 `oryxos chat` 或 `POST /agents/{name}/invoke` 手动补跑一次

#### Demo 二：每日科技日报

**验证能力**：能力四（Plugin Tool 零代码 + MCP）、能力三（Memory）、定时任务

**场景**：每天早上 9 点，Agent 自动汇总当日科技新闻并推送，日报内容会体现用户之前提过的关注方向（如"更关注 AI 和芯片"）。业务方全程不写 Java 代码。

**验收标准**：
- [ ] 业务方只写 `AGENT.md`（含 `schedules` 字段）并配置 `mcp_servers.yaml`
- [ ] Prompt 只出现 Skill 元数据（name/description/路径），没有正文
- [ ] `tool_invocations` 有对 Agent 本地软连接路径的 `read_file` 记录
- [ ] 未绑定 Skill 不可见
- [ ] 日报体现记忆偏好（用户说过的关注方向）
- [ ] 到点自动运行，也能手动补跑

---

## 四周实施节奏与 AI 编程协作指南

OryxOS 核心阶段按 **4 周节奏**组织，每周 3 小时，合计 12 小时。这是极强的时间约束，必须严格控制范围。

### 整体节奏

| 周次 | 核心任务 | 涉及模块 | 验收成果 | 时间 |
|------|---------|---------|----------|------|
| **第一周** | Provider 抽象 + ReAct Loop | `oryxos-core` `oryxos-provider` `oryxos-channel-cli` `oryxos-cli` | `oryxos chat` 多轮对话，Agent 调 HTTP Tool | 3h |
| **第二周** | Memory + Tool 体系 | `oryxos-memory` `oryxos-tool` | Agent 记偏好，调本地文件和 MCP server | 3h |
| **第三周** | Web Service | `oryxos-web` `oryxos-storage` | 10 个 REST 端点完整调用 | 3h |
| **第四周** | 多 Agent + 工程化收尾 | 所有模块收尾 | 多 Agent 并存，Session 跨重启恢复，两个 Demo 跑通 | 3h |

### 各周详细任务拆解

#### 第一周（3 小时）：对接 LLM + ReAct 循环

**关键任务**：
1. Maven 多模块骨架（9 个模块）+ `oryxos init` 工作区初始化
2. `ProviderService` 包装 Spring AI Alibaba（先跑通 DeepSeek 或 Kimi，**必须实现显式 provider 映射**）
3. `ReActLoop` + `PromptBuilder` + `ToolExecutor`
4. 一个内置 HTTP Tool + `CliChannel`
5. Session 内存版（第四周加 SQLite）

**验收标准**：`oryxos chat` 多轮对话，Agent 通过 ReAct 循环调用 HTTP Tool 完成简单任务

**AI 编程要点**：
- ⚠️ 重点检查：Provider 映射是否显式，没有类型扫描
- ⚠️ 重点检查：ReAct 循环是否自己实现，没有用 Spring AI 自动 tool 执行
- 核心循环约数十行 Java，不要过度设计

#### 第二周（3 小时）：Memory + Tool 体系

**关键任务**：
1. `MemoryService` 三层门面 + `LongTermMemory`（`MEMORY.md` 读写）
2. `save_memory` + `recall_memory` 两个内置 Tool
3. `PromptBuilder` 加 Memory 注入
4. 文件 Tool + Shell Tool（`Sandbox` 接口 + `WhitelistSandbox` 应用层白名单）
5. `McpClientService`（连接外部 MCP server）
6. `ContextLoader` 加载 `AGENT.md` 正文及 Skill 软连接元数据

**验收标准**：Agent 记住偏好并后续用到，调本地文件读写、调外部 MCP server 完成跨工具任务

**AI 编程要点**：
- Memory 注入要在每次组装 Prompt 时重新读取 `MEMORY.md`，不缓存
- Sandbox 白名单检查要在 Tool 执行前做，不是执行后
- MCP Client 先实现 stdio transport（最常用），SSE 放扩展

#### 第三周（3 小时）：Web Service

**关键任务**：
1. `WebServer`（Spring MVC + virtual thread）
2. 六个 `ApiController` 的核心 10 个端点
3. `GlobalExceptionHandler`、`ConfigLoader`（配置与密钥加载）

**验收标准**：外部系统通过 10 个 REST 端点完整调用 OryxOS

**AI 编程要点**：
- 核心阶段不做认证、SSE 流式、WebSocket、限流
- 错误响应用统一 JSON 格式（`ApiResponse`）
- 端点实现要委托给核心层服务，Controller 只做参数校验和响应包装

#### 第四周（3 小时）：多 Agent 演示 + 工程化收尾

**关键任务**：
1. 多 Agent 演示（两个不同 Profile 的 Agent 在同一实例并存）
2. Session 持久化到 SQLite（**含 `tool_invocations`、`llm_calls` 写入**）
3. `ContextLoader` 的 Bootstrap 加载
4. Picocli 12 个命令完整
5. 结构化日志
6. `AgentScheduler` 第三触发源（定时任务）
7. 项目主页（VitePress 或类似）
8. **两个 Demo 跑通**（每日天气、每日科技日报）

**验收标准**：多 Agent 并存可用，CLI 体验流畅，Bootstrap 影响 Agent 行为，Session 跨重启恢复，定时任务到点自动触发，主页可访问，两个 Demo 完整跑通

**AI 编程要点**：
- ⚠️ 最后检查：审计表写入是否完整（成功和失败都记录）
- SQLite 迁移不要用 `hibernate.ddl-auto=update`，手动维护建表脚本
- 定时任务用 Spring 的 `ThreadPoolTaskScheduler` + `CronTrigger`，不用 `@Scheduled` 注解

### AI 编程协作最佳实践

#### 准备阶段：建立 Constitution

在开始编码前，先建立项目 Constitution（非协商原则）：

1. 把本文档的"不可违背的原则"章节作为 Constitution
2. 每次 AI agent 开始一个新任务时，让它重读 Constitution
3. 代码 review 时优先检查是否违反 Constitution

#### 实施过程：防止跑偏

**每个 User Story 开始前**：
- [ ] 让 AI agent 重读本文档的相关章节
- [ ] 明确告知"这周的核心任务是 XXX，涉及模块 YYY，验收标准是 ZZZ"
- [ ] 提醒最容易出错的点（如 Spring AI 边界、Provider 映射方式）

**每个 User Story 完成后**：
- [ ] 人工检查代码是否违反 Constitution
- [ ] 运行验收标准里的检查清单
- [ ] 发现偏离立刻让 AI agent 重读 Constitution 并修正

**跨 Task 上下文丢失时**：
- 让 AI agent 读最近的代码
- 明确指出"前面已经实现了 XXX，现在要基于它实现 YYY"

#### 常见问题的应对

| 问题 | AI agent 的典型表现 | 应对 |
|------|-------------------|------|
| 忘记 Constitution | 写出了 Spring AI 自动 tool 执行的代码 | 让它重读 Constitution 原则二，明确指出错在哪 |
| 过度设计 | ReAct 循环写了几百行，各种抽象和扩展点 | 提醒"核心循环约数十行 Java，保持简单" |
| 漏掉审计 | Tool 执行完没写 `tool_invocations` | 提醒 Constitution 原则五，审计是 day one 能力 |
| 异步化倾向 | 用 CompletableFuture 包装 LLM 调用 | 提醒 Constitution 原则七，核心阶段全同步 |

### 关键里程碑检查点

**第一周结束**：
- [ ] `oryxos chat` 能跑通多轮对话
- [ ] Agent 能调用 HTTP Tool
- [ ] Provider 是显式映射，不是类型扫描
- [ ] ReAct 循环是自己实现，不是 Spring AI 自动执行

**第二周结束**：
- [ ] Agent 能记住用户偏好（写入 `MEMORY.md`）
- [ ] Agent 能在后续对话中用到之前记的东西
- [ ] 能调用本地文件操作 Tool
- [ ] 能连接外部 MCP server

**第三周结束**：
- [ ] 10 个 REST 端点都能正常调用
- [ ] 外部系统能通过 API 创建会话、发消息、查历史
- [ ] 健康检查和运行信息端点返回正确数据

**第四周结束（最终验收）**：
- [ ] 两个不同 Agent 能在同一实例并存
- [ ] Session 数据持久化到 SQLite，重启后能恢复
- [ ] `tool_invocations` 和 `llm_calls` 审计表有完整记录
- [ ] 定时任务到点自动触发
- [ ] **Demo 一（每日天气）完整跑通**
- [ ] **Demo 二（每日科技日报）完整跑通**
- [ ] 项目主页可访问

---

## 常见陷阱与解决方案

以下是 AI 编程时最容易踩的坑，按严重程度排序：

| 陷阱 | 症状 | 修复 | 严重程度 |
|------|------|------|----------|
| Spring AI 自动执行 tool | Tool 被调两次，结果重复 | 禁用 `ChatClient` 的自动 tool 执行，由 `ToolExecutor` 接管 | ⚠️⚠️⚠️ 高 |
| Provider 靠类型扫描区分 | 多 Provider 时路由错乱 | 改用显式 `Map<String, ChatModel>` 映射 | ⚠️⚠️⚠️ 高 |
| 审计表只写日志不落库 | 扩展阶段审计功能需要反解析日志 | `tool_invocations` + `llm_calls` 核心阶段就写入 SQLite | ⚠️⚠️ 中 |
| `AGENT.md` / 子指令放进 Tool 模块 | Agent 目录被当 Tool 注册，执行时报错 | 归 `ContextLoader`：正文注入 system prompt，子指令/脚本经 read_file/shell 按需取 | ⚠️⚠️ 中 |
| 用 `hibernate.ddl-auto=update` 迁移表结构 | SQLite ALTER TABLE 报错 | 手动维护建表脚本或引入 Flyway | ⚠️⚠️ 中 |
| 在 ReAct Loop 里用异步 | 复杂度激增，Virtual Thread 优势消失 | 保持同步阻塞，Virtual Thread 自动处理 IO 等待 | ⚠️ 低 |
| `MEMORY.md` 超过 4000 字不截断 | 注入 system prompt 超 context window | `LongTermMemory.truncateIfNeeded()` 超阈值保留最近内容 | ⚠️ 低 |
| Tool 模块拆成多个 | 模块间依赖混乱 | 内置 Tool + MCP Client 合并为一个 `oryxos-tool` 模块 | ⚠️ 低 |

### 陷阱详解：如何避免

#### 1. Spring AI 自动执行 tool（最高频陷阱）

**为什么容易犯**：Spring AI 的官方文档和示例代码大量展示 `.tools()` 链式调用，AI 助手会自然地模仿这些示例。

**检查清单**：
- [ ] 搜索代码里所有 `chatClient` 或 `ChatClient` 的调用
- [ ] 确保没有 `.tools()` 链式调用
- [ ] 确认 Tool 调用都经过 `ToolExecutor`
- [ ] 运行时检查 `tool_invocations` 表，每个 tool 调用只有一条记录

#### 2. Provider 类型扫描

**为什么容易犯**：Spring 的依赖注入习惯是"按类型自动装配"，AI 助手会自然想到 `@Autowired List<ChatModel>`。

**正确做法示例**：
```java
@Configuration
public class ProviderConfig {
    @Bean
    public Map<String, ChatModel> providerMap(
        @Qualifier("deepseekChatModel") ChatModel deepseek,
        @Qualifier("qwenChatModel") ChatModel qwen) {
        return Map.of(
            "deepseek", deepseek,
            "qwen", qwen
        );
    }
}
```

#### 3. 审计表写入

**检查清单**：
- [ ] 每次 `ToolExecutor.execute()` 调用后都写 `tool_invocations`
- [ ] 每次 `ProviderService.call()` 调用后都写 `llm_calls`
- [ ] 成功和失败都记录
- [ ] 记录时间戳、耗时、token 数

### 实施过程中的常见挑战与对策

| 挑战 | 对策 |
|------|------|
| **AI agent 跑偏 constitution** | 每次跑完 implement 后人工检查，发现偏离立刻让 AI agent 重读 constitution 修正 |
| **跨 user story 的上下文断裂** | 每个 user story 开始前让 AI agent 重读 `spec.md` + `plan.md` + 最近代码 |
| **MCP server 集成踩坑** | US-4 实施 MCP 前先用一个最简的 MCP server 测试连通性（stdio transport 可能遇到 process 启动失败、编码问题） |
| **Java 工程基础是前提** | 实施前确保团队成员对 Spring Boot + Maven + JPA 有基本掌握 |

---

## 设计原则与价值主张

### 核心设计原则

1. **底座优先于 Agent**：最重要的交付不是某个强大的 Agent，而是让任意 Agent 可靠运行的环境
2. **自实现核心，复用管道**：ReAct 循环手写；LLM 协议适配委托给 Spring AI Alibaba
3. **一个目录 = 一个 Agent**：一个业务 Agent 由一个目录定义——`AGENT.md`（frontmatter 配置 + 正文指令）、可选 `skills/` 公共 Skill 软连接与 `scripts/`；Skill 元数据每轮注入，正文/附属资源经 `read_file`/`shell` 按需取用
4. **对接开放标准**：工具用 MCP，Agent 间协作用 A2A，Agent 目录借 Anthropic Agent Skills 的形态（目录 + 渐进式披露）
5. **无状态实例，状态外置**：这是未来走向分布式架构而不需要大改设计的前提
6. **安全是地基，不是补丁**：工具来源管控、最小权限、强制沙箱白名单、凭证走环境变量、完整审计记录从第一天就写入 SQLite
7. **分阶段克制**：先构建最小完整的运行时内核；治理和分布式基础设施在真实使用数据验证后再做

### 价值主张（四个词）

| 目标 | 说明 |
|------|------|
| **统一** | 企业内多个 Agent 共享一套底座，Channel、Provider、Tool、Memory、Sandbox 这些公共能力下沉到 OryxOS |
| **私有** | 数据完全留在企业自己的基础设施上，部署在企业自己的 K8s、虚拟机或物理机上 |
| **易接入** | 基于 Spring Boot 标准工程结构，跟企业现有 ERP、CRM、CMDB、SSO、监控系统直接对接，运维工具链复用现有 Java 生态 |
| **可观测** | 标准 Prometheus 指标、结构化 JSON 日志、健康检查接口、Web 管理台 |

### 与相邻项目的边界

| 维度 | OryxOS | Dify/Coze（编排平台） | LangChain/Spring AI（框架） |
|------|--------|---------------------|---------------------------|
| 产物 | 配置出来的常驻 Agent | 可执行的 workflow 流程 | 代码（库/SDK） |
| 使用者 | 业务方（配置）+ 开发者（写 Tool） | 业务人员/开发者（拖拽编排） | 开发者（写代码） |
| 定位 | 运行时底座 | 上层应用工具 | 底层组件库 |

OryxOS 既复用了框架（拿它当 LLM 调用的底层组件），又托住了编排平台（给它当后端运行时），自己专注守在"运行时"这一层。
