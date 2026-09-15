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
| 持久化 | SQLite（默认零配置）/ PostgreSQL 14+（部署选项，url 自动识别）+ Spring Data JPA；表结构由 Flyway 管理（`db/migration/{vendor}/`） |
| 日志 | Logback + SLF4J（结构化 JSON） |
| 构建 | Maven 多模块 |

---

## 模块结构（14 个）

```
oryxos/
├── oryxos-core          # 核心抽象：OryxTool 接口、Session、Profile、ContextLoader、
│                        #   ReActLoop、PromptBuilder、ToolExecutor、AgentService
├── oryxos-persona       # 025 人格库（copy-in 模板库）：PersonaPresetCatalog（内置 12 只读、
│                        #   classpath）、PersonaStore/PersonaService（.oryxos/personas/ 自定义 CRUD）
├── oryxos-provider      # 能力一：ProviderService、Function Calling 适配、
│                        #   多 Provider 显式映射
├── oryxos-memory        # 能力三：MemoryService 门面、LongTermMemory 三档后端、
│                        #   MemoryRecallEngine 三路召回 + MemoryVectorIndex（015）、
│                        #   MemoryTools（save/recall）
├── oryxos-knowledge     # 知识库（014）：LocalKnowledgeBackend（契约第一个插件）、
│                        #   解析/切分/向量化索引流水线、双路召回+RRF 检索、
│                        #   ChunkStore 可插拔存储、KnowledgeTools（retrieve_knowledge）
│                        #   （契约与绑定服务在 oryxos-core/knowledge/，依赖倒置）
├── oryxos-tool          # 能力四：内置 Tool（文件/Shell/HTTP）、MCP Client、
│                        #   ToolRegistry、SandboxChecker
├── oryxos-channel-cli   # CLI Channel：oryxos chat 实现
├── oryxos-channel-feishu # 飞书 IM 入站渠道（017）：oapi-sdk 长连接收 im.message.receive_v1、
│                        #   FeishuEventNormalizer（@ 判定/剥离）、FeishuMessageSender（分段+沙箱）
│                        #   （入站渠道契约与共享编排在 oryxos-core/channel/，依赖倒置）
├── oryxos-channel-wecom  # 企微智能机器人入站渠道（对称飞书）：长连接收消息、免公网回调，
│                        #   WeComEventNormalizer（@ 判定/剥离）、WeComMessageSender（分段+沙箱）
│                        #   （入站渠道契约与共享编排在 oryxos-core/channel/，依赖倒置）
├── oryxos-channel-dingtalk # 钉钉机器人入站渠道（对称飞书/企微）：Stream 长连接收消息、
│                        #   DingTalkEventNormalizer（@ 判定/剥离）、DingTalkMessageSender（分段+沙箱）、
│                        #   断线自动重连（对齐企微）
│                        #   （入站渠道契约与共享编排在 oryxos-core/channel/，依赖倒置）
├── oryxos-web           # 能力五：WebServer、ApiController、GlobalExceptionHandler、
│                        #   OpenAPI
├── oryxos-storage       # 持久化：SQLite、SessionRepository、
│                        #   ToolInvocationRepository、LlmCallRepository
├── oryxos-cli           # 命令行入口：Picocli 主入口、13 个子命令、ConfigLoader（025：agent import）
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

### 原则四：一个目录 = 一个 Agent；Skill 以本地软连接绑定并渐进披露

**一个目录 = 一个 Agent**：`.oryxos/agents/<name>/` 里 `AGENT.md` = frontmatter（运行配置）+ 正文（任务指令），外加可选 `skills/`（Skill 绑定视图）、`scripts/`、`REFERENCE.md`。`AgentLoader.deriveProfile(agentDir)` 把 frontmatter 派生成底座认识的 `Profile`；`.oryxos/profiles/` 取消。

公共 Skill 实体统一存放在 `.oryxos/skills/<name>/`。Agent 可见的 Skill 只由 `.oryxos/agents/<agent>/skills/<name>` 下指向公共实体的**相对软连接**表达；软连接集合是唯一绑定真相源，`AGENT.md` frontmatter 不再声明 `skills:`。

加载走三层渐进式披露：每轮 prompt 只注入当前 Agent 已绑定 Skill 的 `name + description + 本地绝对读取路径`；模型命中后用 `read_file` 读取 `SKILL.md` 正文；Skill 附属参考/脚本继续按需读取或运行。不得预载正文、不得新增 `use_skill`、Skill 不进 `ToolRegistry`。CRUD 与启动恢复必须检测 dangling/escaped/invalid-target/name-mismatch/stale-reference；公共 Skill 被引用时默认拒绝删除并返回引用 Agent。

### 原则五：审计表 Day One 写入

`tool_invocations` 和 `llm_calls` 两张审计表**核心阶段就必须写入**（不需要查询接口，但写入不能省）。不得以"日志够了"为由跳过落库，可审计是 OryxOS 的核心差异化能力。

> **工具治理层（020）**：沙箱白名单之上另有独立的 Tool Policy 减法层——全局/Agent 级工具 allow/deny
> （`tool_policy_rules` 表，管理台可编辑热更新）。两者正交：策略管「这个 Agent 能不能用这个工具」，
> 沙箱管「工具执行时能碰什么资源」，策略放行不豁免沙箱。被策略拒绝的调用照写 `tool_invocations`
> 且带 `blocked_by='policy'` 标记。

### 原则六：不使用 Java SecurityManager；软连接必须校验真实路径

`SecurityManager` 在 JDK 17 起废弃、JDK 21 已不可用。Sandbox 通过 `SandboxChecker` 的 Path / Pattern 白名单实现：
- 文件操作：路径白名单（`file.allowed_paths`）
- Shell：可执行文件精确白名单（`shell.allowed_commands`）；参数以 argv 直传，不解释 Shell 语法。将解释器列入白名单是管理员对本机代码执行权限的显式授予，不构成隔离
- HTTP：域名通配符白名单（`http.allowed_domains`）
- SMTP：端点白名单（`smtp.allowed_endpoints`，按 `host:port` 精确放行，端口缺省=任意，空=deny-all）

文件目标存在时必须用 `toRealPath()` 校验真实路径仍位于白名单根；新建路径校验最近存在父目录的真实路径。Agent Skill 绑定只允许指向 `.oryxos/skills/` 的相对软连接，拒绝绝对链接和越界链接。

### 原则七：同步执行模型

核心阶段全程同步阻塞，配合 Java 21 Virtual Thread 处理并发。**不引入** Reactor / WebFlux / CompletableFuture 等异步编程模型（SSE 流式响应放扩展阶段）。

### 原则八：Tool 模块三合一

内置 Tool、MCP Client 合并在一个 `oryxos-tool` 模块，**不拆成多个模块**。`AGENT.md`（及 Agent 目录里的子指令）加载归 `oryxos-core` 的 `ContextLoader`。

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

### Docker 部署形态（与 bin/start.sh 并行的纯增量）

- 镜像内**不跑 Maven**：jar 平台无关，由构建方原生构建一次，Dockerfile 只 `COPY` 胖 jar 进 JRE 基础镜像——多架构（amd64/arm64）构建因此无需 QEMU 模拟 Maven。
- `docker/docker-entrypoint.sh` 是 start.sh 的容器等价物，三处刻意不同：`exec` 前台（java 即 PID 1，SIGTERM 直达优雅停机）、首启非交互（从模板生成配置后照常启动，零 key 可 boot）、日志走 stdout（交 `docker logs`）。
- 全部状态在 `/data` 卷（`config/` + 工作区 + `oryxos.db` + `logs/`）；`ORYXOS_ROOT=/data/.oryxos` 走环境变量原生支持。镜像非 root（uid 1000）+ 内置 healthcheck（`/api/v1/health`）。
- 流水线：`ci.yml` 的 `docker-build` job 做 PR 门禁（只构建不推送）；`release.yml` 在 tar.gz Release 之后 buildx 推 `ghcr.io/oryx-labs/oryxos:v<版本>` + `:latest` 到 GHCR（需 `packages: write`，已加）。
- 本地构建：`make docker`（依赖 `make build` 的胖 jar）。改 Dockerfile/entrypoint/.dockerignore 时，`docker-build` 门禁会自动验证。
- 停机行为：曾实测（2026-08）SIGTERM 能被处理，但飞书 SDK `ws.Client.close()` 存在阻塞不返回的实现路径，把停机拖满 ~32s（exit 143）。现 `FeishuChannelAdapter.closeQuietly` 将 close 放守护线程限时 join（2s 超时放弃等待），stop 不再阻塞 `ChannelAdminService.stopAll` 与进程停机链路；`spring.lifecycle.timeout-per-shutdown-phase: 10s` 另兜 SmartLifecycle 阶段（注意 destroy-method 回调不受其约束，长连接类资源须各自限时关闭）。compose 的 `stop_grace_period: 40s` 保留作余量；裸 `docker stop`（默认 10s）与 `bin/stop.sh`（等 10s kill -9）在限时关闭下均不再踩超时。

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
  fallback:               # 023 可选：有序备用 Provider——单次 LLM 调用的 provider 侧故障按序切换重发，
    - name: qwen          #   每次尝试各写一条 llm_calls（主备留痕）、切换 WARN 带 traceId；业务性失败（400 类）不切
      model: qwen-plus
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
| `trace_id` | VARCHAR(64) | 单轮处理串联标识（021，可空；一次消息处理=一个 trace） |
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
| `trace_id` | VARCHAR(64) | 单轮处理串联标识（021，可空；与 tool_invocations/agent_executions 同值） |
| `duration_ms` | BIGINT | 调用耗时 |
| `created_at` | TIMESTAMP | 调用时间 |

> **表结构变更（025 起 Flyway 管理）**：迁移脚本在 `oryxos-storage` 的 `db/migration/sqlite/` 与 `db/migration/postgresql/` 双轨目录，同一变更两 vendor 各写一份（同版本号）、只增不改；025~028 期间只做加列/加表/加索引类前向兼容变更。**不要**依赖 `hibernate.ddl-auto=update`（保持 `none`）；V1~V5 是存量收敛序列（幂等），V6 起写非幂等干净 SQL——history 表保证恰好一次。

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
| `shell` | `ShellTools` | 执行命令，命令白名单 + argv 直传 + 超时 |
| `http_get` | `HttpTools` | GET 请求，默认放行 + SSRF 黑名单 |
| `http_post` | `HttpTools` | POST 请求，域名通配符白名单 |
| `save_memory` | `MemoryTools` | 追加到长期记忆（core/archival 分区显式指定） |
| `recall_memory` | `MemoryTools` | 检索归档记忆；配置全局 `embedding.*` 后为语义+关键词+时间三路加权融合（015），未配置保持关键词行为 |
| `notify` | `NotifyTools` | 推送到全局注册表按名引用的通知渠道；webhook/feishu/wecom/dingtalk 走 `WebhookNotifyAdapter`，email 走 `EmailNotifyAdapter`（`config` 多字段 + SMTP 端点白名单） |

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

多副本部署（026）：`oryxos.cluster.enabled=true`（默认 false=单机档零变化）+ 每副本唯一 `instance-id` + 共享 PostgreSQL。
正确性由一条 CAS 认领原语保障：session turn 租约（同会话恰好一次、跨副本排队）、调度到点认领（恰好一次，
fireTime 取 CronTrigger 理论触发时刻绝非墙钟）、事件回执去重（替换进程内 Map）、企微连接属主（永不互踢）、
instances 心跳（GET /api/v1/instances）。误配组合（cluster + SQLite/markdown 记忆/memory 知识库）启动即拒。
崩溃轮次标失败不重放，用户重发恢复。

文件面分布式（027）：`.oryxos/` 工作区放共享卷（只依赖读写可见 + rename 原子，不依赖文件锁/inotify，
支持矩阵见 `docs/SharedVolumeGuide.md`）。变更感知走 `workspace_versions` 版本号总线——管理写路径落盘后
bump 对应域（agents/skills/personas/knowledge），各副本按 `workspace-poll-interval`（默认 1s）轮询重载，
集群档不装 WatchService watcher（单机档 watcher 零回归）；全部工作区写入经 `AtomicFiles` 原子改名落盘。
知识索引重建经 `knowledge_build_claims` CAS 认领恰好一次（冲突 409、崩溃 TTL 后接管），检索恒读
`knowledge_generations` 已提交代次；导入索引段与重建同认领互斥（排队不丢）。运维直接改盘走
`POST /api/v1/workspace/refresh` 逃生舱。

容器交付（039）：官方 Helm Chart（`charts/oryxos/`，`docs/K8sDeployGuide.md`）——必填仅两项密文引用
（数据库三键 + `ORYXOS_MASTER_KEY`，K8s Secret→环境变量走 022 原生面），集群档默认开、工作区 RWX PVC、
liveness/readiness 探针（readiness 含 db）、RollingUpdate 0/1 + preStop + grace 40s。`server.shutdown=graceful`
已入 boot 默认（在途请求排空=timeout-per-shutdown-phase）；`ChannelAdminService.stopAll` 停机先释放渠道属主
租约（接管秒级不等 TTL）。OTel trace 可选导出（`oryxos.otel.endpoint`，不配零开销）：`SpanRecorder` 契约在
core（MetricsRecorder 同款 NOOP 纪律），turn/llm/tool 三 span 与审计同 traceId 同计时区间事后补记，
turn 根 spanId = traceId 前 16 hex 确定性父子。门禁：`make helm-lint`（lint/template/kubeconform/断言）+
ci helm job 的 kind 安装冒烟；mock provider 可 `-Doryxos.mock.latency-ms` 注入固定时延供吞吐压测。

落库凭证（providers.api_key、notify_channels.config 敏感项）经主密钥 AES-GCM 加密存储（022，`enc:v1:` 前缀）：`ORYXOS_MASTER_KEY` 环境变量优先，缺省 `.oryxos/master.key` 首启自动生成；密钥不匹配启动即拒并指路恢复。

企业 SSO（040）：管理台可选标准 OIDC 授权码 + PKCE 登录（`oryxos.web.oidc.*`，默认关零变化；引 `nimbus-jose-jwt` 单 jar 验签，不引 Spring Security）。本地账密恒并存（IdP 宕机不锁死）；身份锚 `iss+sub` 落 `oidc_identities`，未映射用户 JIT 供给（默认 VIEWER）；角色条件权威——配置了 claim 映射且命中则每登刷新，否则本地权威（`oryxos user role` 有效）；认证事件（本地+OIDC 登录/登出/映射/角色变化）落 `auth_events`（V10），令牌内容零落库零日志；state/nonce/PKCE 走 `oidc_auth_requests` CAS 单次消费（多副本可用）。行为表详见 `docs/OidcSsoGuide.md`。

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
| 用 `hibernate.ddl-auto=update` 迁移表结构 | SQLite ALTER TABLE 报错 | 写 Flyway 迁移脚本（`db/migration/{vendor}/` 双轨各一份，只增不改） |
| 在 ReAct Loop 里用异步 | 复杂度激增，Virtual Thread 优势消失 | 保持同步阻塞，Virtual Thread 自动处理 IO 等待 |
| `MEMORY.md` 超过 4000 字不截断 | 注入 system prompt 超 context window | `LongTermMemory.truncateIfNeeded()` 超阈值保留最近内容 |
| Tool 模块拆成多个 | 模块间依赖混乱 | 内置 Tool + MCP Client 合并为一个 `oryxos-tool` 模块 |

---

## 设计原则

- **底座优先于 Agent**：最重要的交付不是某个强大的 Agent，而是让任意 Agent 可靠运行的环境
- **自实现核心，复用管道**：ReAct 循环手写；LLM 协议适配委托给 Spring AI Alibaba
- **一个目录 = 一个 Agent**：一个业务 Agent 由一个目录定义——`AGENT.md`（frontmatter 配置 + 正文指令）、可选 `skills/` 公共 Skill 软连接与 `scripts/`；Skill 元数据每轮注入，正文/附属资源经 `read_file`/`shell` 按需取用
- **对接开放标准**：工具用 MCP，Agent 间协作用 A2A，Agent 目录借 Anthropic Agent Skills 的形态（目录 + 渐进式披露）
- **无状态实例，状态外置**：这是未来走向分布式架构而不需要大改设计的前提
- **安全是地基，不是补丁**：工具来源管控、最小权限、强制沙箱白名单、凭证走环境变量、完整审计记录从第一天就写入 SQLite
- **分阶段克制**：先构建最小完整的运行时内核；治理和分布式基础设施在真实使用数据验证后再做
