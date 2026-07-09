# OryxOS 需求文档

> 本文档定义 OryxOS 项目的功能需求和非功能需求，作为后续技术方案设计、研发实施、测试验收的依据。本文档回答 **What**，不回答 **How**，How 在后续的技术方案中展开。前置阅读《项目篇 OryxOS 业界调研》，本文档基于调研得出的领域判断，不重复论证企业 Agent OS 领域的现状。

---

## 1. 项目概述

### 1.1 OryxOS 是什么

OryxOS 是基于 Java 实现的面向企业场景的 **Agent OS**。它装在企业自己的 K8s 或服务器上，作为统一底座，在底座上跑各种业务 Agent（运维助手、客服助手、HR 助手、销售助手、知识管理助手等），共享一套渠道接入、模型路由、工具调用、记忆系统、沙箱执行能力。数据完全留在企业自己的基础设施，不锁任何云生态。

业界已经有开源 Agent 项目把这套设计验证过（OpenClaw 用 Node.js，Hermes Agent 用 Python），但 Java 生态没有任何项目把"Agent OS"作为定位。Java 是大量企业现有后端的事实标准技术栈，Spring AI Alibaba 已经把底层 LLM 调用解决了，缺的就是上面那一层"Agent OS"。OryxOS 填这个位置。

#### Agent OS vs Agent Runtime 的分层

**Agent OS** 跟 **agent runtime**（Agent 运行时）不是一回事：

- **agent runtime**：让单个 Agent 跑起来的执行内核，负责 LLM 调用、工具执行、上下文管理、循环控制
- **Agent OS**：内核包含一个 agent runtime，但在 runtime 之上还要管多个 Agent 的生命周期、统一的对外对内接入、统一记忆、多租户、审计这些 OS 级治理能力

借操作系统类比，runtime 像单个进程的执行环境，Agent OS 像管理一群进程、调度资源、提供共享服务和治理的那层。一句话：runtime 让一个 Agent 跑起来，**Agent OS 让一群 Agent 在企业里被管起来**。

#### 交付分两段

理解这个分层，才能看懂 OryxOS 的交付节奏：

1. **核心阶段**：先把 Agent OS 的运行时内核用 Java 做扎实，这一层在能力上对齐业界开源 Agent OS 的基础层
2. **扩展阶段**：OryxOS 真正的差异化治理层（多租户、SSO、完整审计、Tool 治理），在核心内核之上由扩展阶段和社区共建陆续补齐

核心阶段交付的是 Agent OS 的**内核底座**，而不是一个治理能力完备的企业级 Agent OS，后者是终局，核心阶段是地基。

---

### 1.2 OryxOS 能干什么

OryxOS 优先做五个核心能力，基于这五个能力可以扩展出企业里大量真实需求。这五个能力都属于"让单个 Agent 跑得好"的运行时内核层；让 OryxOS 成为真正"OS"的多 Agent 治理能力（多租户、Tool Policy、审计、SSO），在扩展和社区阶段补齐。

#### 能力一：对接 LLM

OryxOS 通过 Provider 抽象层对接主流大模型（DeepSeek、通义、Kimi、智谱、混元、豆包、Anthropic、OpenAI 等），Agent 不感知具体调的是哪家模型，运行时切换无 lock-in。

**基于这个能力可以做的事：**
- 任意业务场景的自然语言对话助手，Agent 通过 LLM 理解用户意图、给出回复
- 同一个 Agent 在不同任务用不同模型，简单任务走便宜模型、复杂任务走强模型
- 接入企业自有的本地推理服务（Ollama、vLLM），数据完全不出企业
- 多 Provider 编排，做一份报告可以让规划用便宜模型、综合用强模型

#### 能力二：ReAct 循环

ReAct（Reason + Act）是 Agent 的核心工作机制：Agent 接到一个任务后，LLM 思考要不要调工具、调哪个工具，调用之后看结果，再决定下一步，直到给出最终响应。

**基于这个能力可以做的事：**
- Agent 能自主决定何时调用哪个工具，不需要业务方写死流程
- 多步骤任务可以一次对话内连续完成（先读文件、再分析、再调 API、再生成报告）
- Agent 出错时能自己回滚、重试、换工具
- 复杂业务流程不需要预先编排，Agent 在运行时动态决定执行路径

#### 能力三：Memory 三层记忆

Agent 记得住用户的偏好、项目、决策、对话历史。三层记忆设计，核心阶段先实现会话和长期两层，情景记忆放扩展阶段补齐：

| 层次 | 说明 | 核心阶段 |
|------|------|---------|
| 会话记忆 | 当前对话的完整历史，过长时自动压缩 | ✅ 实现 |
| 长期记忆 | 用户偏好、项目背景、关键事实，存在 MEMORY.md 文件里，跨对话保留 | ✅ 实现（极简版） |
| 情景记忆 | 每个任务过程中学到的东西，修改了什么文件、做了什么决策 | ⏳ 扩展阶段 |

**基于这个能力可以做的事：**
- Agent 跨多次对话记住用户偏好（"我一般用 Spring Boot 不用 Spring MVC"）
- 长任务过程中状态保持，对话中断后能恢复继续做
- 团队内多个 Agent 共享同一个用户的偏好记忆
- 历史决策可追溯（"上次为什么选 DeepSeek 不选 Kimi"在记忆里能查到）

#### 能力四：Plugin 自定义工具 + 内置工具集

Agent 能调用工具实际操作系统。OryxOS 提供两类 Tool：

- **内置 Tool**：OryxOS 自带的基础工具（读写文件、执行 Shell、发起 HTTP 请求）
- **Plugin Tool**：业务方自己扩展的工具，按门槛从低到高有三种方式

| 方式 | 门槛 | 做法 | 适用场景 |
|------|------|------|---------|
| 零代码 | 最低 | 写 SKILL.md + 复用社区现成 MCP server | 业务方只描述意图，LLM 自己组合调用 |
| 轻代码 | 中等 | 用任何语言写 MCP server | 接入企业自有系统（ERP、CRM） |
| 重代码 | 最高 | 用 `@Tool` 注解写 Java Spring Bean | 深度集成，性能最好 |

**基于这个能力可以做的事：**
- 给 Agent 接入企业自己的 ERP、CRM、CMDB，让 Agent 真正能干企业的活
- 接 GitHub、Jira、Confluence，做研发助手
- 接 Prometheus、Grafana、SSH，做运维自愈
- 业务方零代码扩展，写 SKILL.md + 复用 MCP，纯 markdown 就能上线新场景

#### 能力五：Web Service

OryxOS 通过完整的 REST API 把所有能力对外暴露，业务系统用 HTTP 调一下就能用上 Agent，不用关心内部怎么实现。Web Service 是 OryxOS 的对外门面，是企业把 AI 能力嵌入已有业务系统的唯一通道。

API 覆盖六类操作：

| 类别 | 端点功能 |
|------|---------|
| 会话管理 | 创建会话、发消息、查历史、归档会话 |
| Agent 调用 | 无状态调用一次 Agent（流式响应扩展阶段补） |
| Profile 管理 | 列 Profile、看详情、重载 |
| Memory 操作 | 查长期记忆、手动写入、清理 |
| Tool 信息 | 列可用 Tool、看元信息 |
| 系统状态 | 健康检查、运行指标、Provider 状态 |

#### 关于 Channel

核心阶段还有一个基础模块是 Channel（消息接入渠道）。Channel 主要解决"消息进来、响应出去"，核心阶段只内置 CLI 一种，企业微信、飞书、钉钉等 IM Channel 放扩展阶段。Channel 是核心功能模块，但它不算"五大核心能力"之一。

#### 五个能力组合可以解决的场景

| 场景 | LLM | ReAct | Memory | Tool | Web Service |
|------|-----|-------|--------|------|-------------|
| 全渠道客服 | 理解用户问题 | 循环调知识库 | 记住客户历史 | 接 CRM | HTTP 接入客服系统 |
| 运维助手 | 分析告警 | 调日志查询+重启 | 记住历史故障 | 接 Prometheus/SSH | Webhook 触发 |
| 研发助手 | 理解需求 | 读代码改代码 | 记住项目惯例 | 接 GitHub/CI | IDE 插件接入 |
| 知识管理 | 理解问题 | 检索文档 | 记住团队约定 | 接 Confluence | 内网门户嵌入 |
| 销售助手 | 拼装客户画像 | 调 CRM+企查查 | 记住客户偏好 | 接销售系统 | 销售 App 调用 |
| 数据分析 | 生成 SQL | 执行查询+出图 | 记住业务表结构 | 接 BI 系统 | BI 工具集成 |

---

### 1.3 文档定位

本文档按三档分级定义 OryxOS 的功能需求：

1. **核心功能**：最短链路，跑通"配置一个 Agent、跟它对话、它能调用工具"这件事，对应 Agent OS 的运行时内核
2. **扩展功能**：生产级使用必需但不在核心链路上的能力，包含企业级治理层（多租户、SSO、审计、Tool Policy）
3. **社区共建功能**：长期方向，开放给社区贡献

核心阶段按 **4 周节奏**组织，每周 3 小时实践，合计 12 小时。这是极强的时间约束，核心功能范围必须收得很紧，只覆盖运行时内核的最短跑通链路。

---

## 2. 术语和概念

为避免歧义，统一核心术语定义（对齐业界开源 Agent OS 事实标准）：

| 术语 | 定义 |
|------|------|
| **Agent（智能体）** | 一个具象的业务智能体，"这个 Agent 是干什么的"由 Skill 定义（做什么、什么时候该做），"这个 Agent 怎么跑起来"由 Profile 绑定（用哪个模型、能用哪些工具、绑定哪个渠道、要不要定时）。Skill + Profile 绑定后才是一个完整可用的业务 Agent，不是写代码写出来的 |
| **Profile（配置）** | Agent 的运行时宿主配置，是 Agent OS 内核层的能力，不是 Agent 本身——它决定一个 Agent"怎么跑"：绑定的 LLM Provider、可用 Tool 列表、绑定 Channel、Tool Policy、引用的 Skill、定时规则。没有绑定任何 Skill 的 Profile 只是一个通用助手骨架，不是这个项目要交付的业务 Agent |
| **Provider（供应商）** | LLM API 服务的抽象，实现统一接口让 Agent 不感知具体调的是哪家模型 |
| **ReAct 循环** | Agent 的核心工作机制，Reason + Act。LLM 思考是否调用工具，调用后看结果，再决定下一步，直到给出最终响应 |
| **Tool（工具）** | Agent 可以调用的外部能力。内置 Tool 是 OryxOS 自带的（文件、Shell、HTTP、通知推送）；Plugin Tool 是业务方自己写的 |
| **Memory（记忆）** | Agent 跨对话保留的状态，分三层：会话记忆、长期记忆（MEMORY.md）、情景记忆（扩展阶段） |
| **Channel（渠道）** | Agent 对外接入的消息入口，包括 CLI、企业微信、飞书、钉钉、Slack 等 |
| **Web Service** | OryxOS 对外暴露的完整 REST API，是业务系统集成 OryxOS 的唯一通道 |
| **Session（会话）** | 用户和 Agent 一次对话的上下文容器，包含对话历史、当前上下文、临时变量 |
| **Sandbox（沙箱）** | 工具执行的隔离环境。核心阶段是应用层白名单校验，扩展阶段补容器级隔离 |
| **Tool Policy（工具策略）** | 控制 Agent 可用工具的允许或拒绝规则，在 Profile 级别配置 |
| **Skill（技能）** | Agent 的定义本体——"这个业务 Agent 该做什么"，用 SKILL.md 文件描述（frontmatter 加任务说明正文），兼容 agentskills.io 开放标准。业务方定义一个新 Agent，写的就是一份 Skill |
| **Bootstrap（引导文件）** | 加载到系统提示词中的上下文文件：AGENTS.md（项目级 agent 行为说明）、SOUL.md（agent 人格定义）、USER.md（用户偏好） |
| **Workspace（工作区）** | OryxOS 实例的工作目录，默认是 `.oryxos/`，包含配置、Bootstrap 文件、记忆、会话、技能的子目录 |

---

## 3. 设计目标

OryxOS 的核心目标可以用四个词概括：**统一、私有、易接入、可观测**。

| 目标 | 说明 |
|------|------|
| **统一** | 企业内多个业务 Agent 共享同一套底座。Channel、Provider、Tool、Memory、Sandbox 这些公共能力下沉到 OryxOS，企业上一个新 Agent 通过 Profile 配置一份 YAML 就能跑起来 |
| **私有** | 数据完全留在企业自己的基础设施上，部署在企业自己的 K8s、虚拟机或物理机上。OryxOS 本身不收集任何企业数据 |
| **易接入** | 基于 Spring Boot 的标准 Java 工程结构，跟企业现有的 ERP、CRM、CMDB、SSO、监控系统直接对接，运维工具链复用现有 Java 生态 |
| **可观测** | 标准的 Prometheus 指标、结构化 JSON 日志、健康检查接口、Web 仪表板，适配企业现有监控告警体系 |

---

## 4. 典型场景

以下三个典型场景描述 OryxOS 完整形态（含扩展阶段能力）下的目标用法，核心阶段先具备其运行时内核。

### 场景一：运维助手

某中型 SaaS 公司的运维团队基于 OryxOS 搭一个运维助手，接入企业微信。Agent 配了几个 Tool（告警分诊、日志查询、服务重启、变更审批）。凌晨告警通过 webhook 进 OryxOS，Agent 收到告警后调用日志查询 Tool 拉错误堆栈，跟历史故障库交叉引用发现是已知 bug，自动应用 mitigation Skill 重启服务，在企业微信运维群里汇报"已自愈，详情见附件"，值班工程师早晨起来看下记录就行。

**OryxOS 在此场景的角色**：Channel 接入（企业微信）、Provider 路由（主备 LLM）、Tool 调用（SSH、Prometheus、Slack 通知）、Memory（历史故障库）、Skill（自愈 runbook）。

### 场景二：知识管理助手

某金融企业的法务团队基于 OryxOS 搭一个知识管理 Agent，接入飞书。Agent 索引了内部的合同模板、法规文档、历史案例、咨询记录。员工在飞书里问"上次签 SaaS 服务协议是怎么处理数据出境条款的"，Agent 检索 Memory 拉出历史案例，综合相关法规给出建议草稿，标注引用来源。

**关键点**：Memory 检索准确度和引用追溯（合规要求所有 Agent 回复必须可追溯到引用源）。

### 场景三：销售助手

某制造业企业的销售部门基于 OryxOS 搭一个客户洞察 Agent，接入企业微信和 CRM。销售跑客户前问 Agent"明天去拜访 A 公司，有什么我需要知道的"，Agent 调用 CRM connector 拉客户历史交易记录，调用企查查 MCP 工具查最新工商信息，调用知识库 Tool 提取关键决策人和采购习惯，综合输出客户简报。

**OryxOS 在此场景的核心能力**：MCP 集成（外部数据）、企业 IT 系统 connector（自家 CRM）、Tool 编排。

---

## 5. 核心功能

> 核心功能是核心阶段 4 周（合计 12 小时）内必须完成的最短链路，对应 Agent OS 的运行时内核。目标是跑通一个完整链路：用 Profile 配置一个 Agent，通过 CLI 跟它对话，它能调用 LLM 和工具完成任务，并能通过 REST API 对外暴露。

### 5.1 工作区初始化

OryxOS 的工作目录是 `.oryxos/`，通过 `oryxos init` 命令初始化。

```bash
oryxos init   # 在当前目录下创建 .oryxos/ 工作区
```

初始化后的目录结构：

```
.oryxos/
├── profiles/          # Profile 配置（每个 Agent 一个 YAML）
├── sessions/          # 会话历史
├── skills/            # SKILL.md 文件
├── logs/              # 结构化日志
├── tools/             # 自定义 Tool 配置
├── memory/
│   └── MEMORY.md      # 长期记忆文件
├── AGENTS.md          # Bootstrap：项目级 agent 行为说明
├── SOUL.md            # Bootstrap：默认 agent 人格定义
├── USER.md            # Bootstrap：用户偏好
└── profiles/
    └── default.yaml   # 默认 Profile
```

- 三个 Bootstrap 文件在 Agent 启动时被自动加载到系统提示词，让 Agent 知道项目背景、自己的身份、用户偏好
- `oryxos init` 同时生成一份默认 Profile，用最简配置让用户立刻可用

---

### 5.2 Profile 配置

Profile 是 Agent 的运行时宿主配置，用 YAML 文件描述——决定一个 Agent 绑定哪个 Skill、用哪个 Provider/模型、能用哪些 Tool、绑定哪个 Channel、要不要定时。Profile 本身是 Agent OS 内核层的能力（底座），"这个 Agent 具体做什么"由它引用的 Skill 定义，两者绑定在一起才构成一个完整的业务 Agent。

**Profile YAML 结构：**

```yaml
name: string                    # Profile 名称
description: string             # 描述

identity:
  agent_name: string            # Agent 名称
  prompt: string                # 人格/系统提示词（或引用 SOUL.md）

provider:
  name: string                  # Provider 名称（deepseek/qwen/kimi 等）
  model: string                 # 模型名
  temperature: float            # 温度参数（可选）

tools:
  - string                      # 可用 Tool 名称列表

skills:
  - string                      # 引用的 SKILL.md 文件列表

mcp_servers:
  - string                      # 引用的 MCP Server 列表

channels:
  - name: string                # Channel 名称
    config: {}                  # Channel 配置

bootstrap:
  - string                      # Bootstrap 文件列表

settings:
  max_iterations: 10            # 最大 ReAct 迭代次数
  max_history_turns: 20         # 最大对话历史轮数
```

**Profile 管理命令：**

```bash
oryxos profile create <name>    # 创建新 Profile
oryxos profile list             # 查看所有 Profile
oryxos profile show <name>      # 查看 Profile 详情
oryxos profile delete <name>    # 删除 Profile
```

核心阶段支持创建并管理多个 Profile，多个 Agent 可以在同一个 OryxOS 实例上并存，这是"OS"在核心阶段的最小体现。

---

### 5.3 Provider 抽象（核心能力一：对接 LLM）

Provider 是 LLM 调用的统一抽象。所有 LLM 调用通过 Provider 接口走，Agent 不感知具体调的是哪家。

核心阶段直接基于 Spring AI Alibaba 的 `ChatClient` 实现。Spring AI Alibaba 已经做好了主流 LLM（DeepSeek、通义、文心、Kimi、智谱、混元、豆包、Anthropic、OpenAI 等）的 connector，OryxOS 把它们包装成 Provider，不重复造轮子。

每个 Provider 实例配置：
- `provider 名`（deepseek、qwen、kimi 等）
- `模型名`
- `API key`
- `可选的 base URL`

**核心阶段不做**：fallback 和 hedge racing。Provider 故障时直接报错给 Agent；成本透明只做基础版（每次 LLM 调用记录 token 使用量、Provider、模型落到日志）。

---

### 5.4 ReAct 循环（核心能力二：Agent 大脑）

ReAct 循环是 Agent 的核心工作机制，也是 OryxOS 最关键的一段代码。

**核心算法（Reason + Act）：**

```
接到用户消息
  └─ 追加到 Session 对话历史
     └─ 组装 Prompt（system prompt + Bootstrap + 对话历史 + 可用 Tool 列表）
        └─ 调用 LLM Provider 获取响应
           ├─ [无 Tool 调用] → 返回最终响应
           └─ [有 Tool 调用] → 执行 Tool，把结果追加到对话历史 → 继续循环
```

达到最大迭代次数（默认 10 次）强制结束。

**实现要点：**
- 核心循环约数十行 Java 代码，自己实现而不依赖 Spring AI 的 Agent 抽象
- 最大迭代次数可在 Profile 里覆盖
- 每次 LLM 调用和 Tool 调用都记录结构化日志

**核心阶段不做**：Tool 调用并行、上下文动态压缩、Agent 间任务委托。

---

### 5.5 Memory 三层记忆（核心能力三：让 Agent 记得住）

**核心阶段实现会话 + 长期两层（情景记忆放扩展阶段）：**

#### 会话记忆

- 当前对话的完整历史，按 Channel + 用户 + Profile 联合标识
- Session 数据持久化到本地 SQLite，重启后可以恢复
- 上下文超过 LLM context window 上限时简单截断早期对话

#### 长期记忆（极简版）

- 存在 `.oryxos/memory/MEMORY.md` 一个 Markdown 文件，跨所有对话保留
- Agent 通过两个内置 Tool 主动读写：
  - `save_memory(content)`：把要长期记住的事追加到 MEMORY.md
  - `recall_memory(query)`：按关键词检索 MEMORY.md 里的相关内容
- Agent 启动时 MEMORY.md 整个文件作为长期上下文注入到 system prompt
- 文件超过 4000 字时简单截断（扩展阶段做压缩）

**核心阶段不做**：自动从对话中抽取事实、语义检索（用关键词匹配）、情景记忆、Memory Wiki、矛盾检测。

**用户核心体验**：用 OryxOS 一段时间后，Agent 自然会记住用户的偏好、项目信息、关键决策，下一次对话不需要重新解释。这是 Agent OS 区别于 chatbot 的核心体验。

---

### 5.6 Tool 体系（核心能力四：让 Agent 能干事）

Tool 是 Agent 可以调用的外部能力。Agent 通过 LLM Function Calling 决定何时调哪个 Tool，OryxOS 负责 Tool 的注册、查找、调用、结果回传。

#### 内置 Tool（核心阶段 5 个）

| Tool | 类型 | 说明 |
|------|------|------|
| `read_file` | 文件 | 读取文件内容，受路径白名单限制 |
| `write_file` | 文件 | 写入文件内容，受路径白名单限制 |
| `list_dir` | 文件 | 列出目录，受路径白名单限制 |
| `shell` | Shell | 执行 bash 命令，有超时和命令白名单限制 |
| `http_get` / `http_post` | HTTP | 发起 HTTP 请求，有域名白名单限制 |
| `save_memory` | Memory | 把内容追加到 MEMORY.md |
| `recall_memory` | Memory | 按关键词检索 MEMORY.md |

#### Plugin Tool（业务方扩展）

| 方式 | 门槛 | 推荐度 | 场景 |
|------|------|--------|------|
| **方式一**：写 SKILL.md + 复用 MCP server | 零代码 | ⭐⭐⭐ 主推 | 描述意图，LLM 自己组合现成能力 |
| **方式二**：自己写 MCP server | 轻代码 | ⭐⭐ | 接入企业自有系统，任何语言皆可 |
| **方式三**：写 Java @Tool Bean | 重代码 | ⭐ | 深度集成，性能最好 |

> **选择原则**：能用方式一就不用方式二，能用方式二就不用方式三。

**零代码示例**：想做"每天早上推送昨日 GitHub PR 评审进度到 Slack"，只需：
1. 写 `daily-pr-digest.md` 描述任务和触发时机
2. 复用社区现成的 `github-mcp` 和 `slack-mcp`
3. 配置 Profile 引用这个 Skill 和两个 MCP server

整个过程不写一行代码。

#### Sandbox 安全隔离

核心阶段用应用层白名单校验实现：
- 文件操作：路径白名单
- Shell：命令白名单
- HTTP：域名白名单
- 执行超时和资源占用限制

> 注：不使用 Java SecurityManager，它在 JDK 17 起已废弃、JDK 21 已不可用。完整的 Docker/K8s 容器级沙箱放在扩展功能。

---

### 5.7 Channel 接入

Channel 是 Agent 对外的消息接入入口，主要解决"消息进来、响应出去"这件事。HTTP 接入归 Web Service，不在 Channel 范畴内。

核心阶段只内置一种 Channel：**CLI Channel**，通过 `oryxos chat` 命令启动，支持多轮对话、查看上下文、查看 Tool 调用记录。

企业微信、飞书、钉钉、Slack 等 IM Channel 放在扩展功能（实现复杂度高，需要 OAuth 和企业资质，不在 12 小时核心阶段能完成的范围）。

---

### 5.8 Web Service（核心能力五：对外接口暴露）

Web Service 是 OryxOS 的对外完整门面，业务系统通过 REST API 接入 OryxOS 的所有能力。这是 OryxOS 区别于偏个人定位的 OpenClaw、Hermes 的关键能力。

#### 核心阶段 10 个关键端点

| 类别 | 端点 | 说明 |
|------|------|------|
| 会话管理 | `POST /api/v1/sessions` | 创建会话 |
| 会话管理 | `POST /api/v1/sessions/{id}/messages` | 发消息 |
| 会话管理 | `GET /api/v1/sessions/{id}` | 查历史 |
| 会话管理 | `DELETE /api/v1/sessions/{id}` | 归档会话 |
| Agent 调用 | `POST /api/v1/agents/{name}/invoke` | 无状态调用 |
| Profile 信息 | `GET /api/v1/profiles` | 列 Profile |
| Memory 操作 | `GET /api/v1/memory` | 查长期记忆 |
| Tool 信息 | `GET /api/v1/tools` | 列可用 Tool |
| 系统状态 | `GET /api/v1/health` | 健康检查 |
| 系统状态 | `GET /api/v1/info` | 运行信息 |

#### 扩展阶段补齐的端点（15 个）

Profile 的 show/reload/create/update/delete；Memory 的 append/clear/search；Tool describe 和调用历史查询；LLM call 历史、token 统计；Webhook 触发、流式 SSE 响应；Prometheus metrics、OpenAPI spec。

**核心阶段不做**：认证机制（无认证假设内网）、流式响应 SSE、WebSocket、RBAC 权限。

#### 业务系统集成场景

| 模式 | 方式 | 适用 |
|------|------|------|
| 同步调用 | `POST /agents/{name}/invoke` 等返回 | Stateless 短任务 |
| 会话保持 | 先创建 Session，后续多次发消息 | 连续对话 |
| Webhook 触发 | 告警系统、CI/CD 通过 Webhook 调 Agent | 事件驱动 |
| 跨语言集成 | 任何能发 HTTP 请求的语言都能接入 | 通用集成 |

---

### 5.9 Session 管理

Session 是用户和 Agent 一次对话的上下文容器，包含起止时间、用户身份、Agent 标识、对话历史、当前上下文、临时变量。Session 标识由 Channel、用户、Agent 联合生成。

核心阶段 Session 数据持久化到本地 SQLite（`.oryxos/sessions/` 下）。重启 OryxOS 后，正在进行的 Session 可以恢复。Session 上下文超过 LLM 的 context window 时，简单截断早期对话保留近期对话。

---

### 5.10 三种运行模式

| 模式 | 命令 | 说明 |
|------|------|------|
| 交互对话 | `oryxos chat` | 交互式多轮对话，开发调试和日常使用的主要方式。`--message "xxx"` 可发单条消息后退出 |
| HTTP API | `oryxos serve` | 启动后在指定端口（默认 8080）开放 RESTful 接口，业务系统通过 HTTP 调用 |
| 守护进程 | `oryxos gateway` | 常驻守护进程，同时服务多个 Channel |

三种模式共享同一份 Profile 配置和 Session 存储。

---

### 5.11 命令行工具

核心阶段实现 **12 个命令**：

| 类别 | 命令 | 说明 |
|------|------|------|
| 启动和状态 | `oryxos init` | 初始化工作区 |
| 启动和状态 | `oryxos status` | 查看配置和运行状态 |
| 启动和状态 | `oryxos chat [--profile <name>]` | 交互对话 |
| 启动和状态 | `oryxos serve` | 启动 HTTP API 服务 |
| 启动和状态 | `oryxos gateway` | 启动多渠道守护进程 |
| Profile 管理 | `oryxos profile list` | 列出所有 Profile |
| Profile 管理 | `oryxos profile create <name>` | 创建新 Profile |
| Profile 管理 | `oryxos profile show <name>` | 查看 Profile 详情 |
| Profile 管理 | `oryxos profile delete <name>` | 删除 Profile |
| 查询 | `oryxos provider list` | 列出已配置的 Provider |
| 查询 | `oryxos tool list` | 列出已注册的 Tool |
| 查询 | `oryxos session list` | 列出会话历史 |

---

### 5.12 配置与密钥加载

核心阶段做基础版：

- 敏感配置通过**环境变量**注入或独立的本地配置文件加载，不明文写死在 Profile YAML 里
- Profile 里用 `${ENV_VAR}` 占位，加载时从环境变量解析
- 配置加载时做基础校验（必填项、格式），缺失或非法时给出清晰报错

完整的加密存储、密钥轮转、对接企业密钥管理系统（KMS、Vault）放在扩展阶段。

---

### 5.13 项目主页

OryxOS 作为开源项目，需要一个独立的主页作为对外门面，讲清楚 OryxOS 是什么、能干嘛、怎么用，引导开发者快速上手。

主页在核心阶段做出来，与核心代码同期发布，作为 OryxOS 1.0 对外亮相的一部分。技术栈推荐使用 VitePress、Astro 或 Docusaurus 等静态站点生成器。

---

## 6. 扩展功能

扩展功能在核心功能完成后推进，补齐生产级使用必需但不在最短链路上的能力，以开源社区方式陆续补齐。

### 6.1 渠道和模型层

- **多 Channel 接入**：企业微信、飞书、钉钉、Slack、邮件，通过 Channel Adapter 插件机制扩展
- **Provider Fallback 和可靠性**：三层 failover（hedge racing、circuit breaker、自动切换），Provider 故障时自动切换备用
- **Adaptive Routing**：LLM 路由从静态配置升级为动态决策，根据任务类型、历史调用质量、当前 Provider 负载自动选择

### 6.2 记忆和能力层

- **Memory 自动抽取**：LLM 在对话结束时自动提取值得长期保留的事实写入 MEMORY.md
- **Memory 语义检索**：集成向量数据库（Milvus、Qdrant、Weaviate、PostgreSQL pgvector），按语义相似度匹配
- **情景记忆**：补齐 Memory 第三层，记录任务过程中修改的文件、决策、成果
- **Memory Wiki**：结构化 claim/evidence、矛盾检测、新鲜度管理
- **Skill 体系**：完整支持 SKILL.md 文件，兼容 agentskills.io 开放标准

### 6.3 工具和安全层

- **MCP Server 暴露**：OryxOS 自己作为 MCP server，把内部 Agent 能力暴露给其他系统
- **Tool Policy**：Profile 级别的 Tool 允许或拒绝规则，这是 Agent OS 治理能力里最轻、最能体现 OS 管控的一项，扩展阶段优先做
- **Tool LRU 加载**：工具数量多时，动态加载，避免把所有工具塞进 LLM context
- **完整 Sandbox 隔离**：Docker 容器和 K8s pod 两种 sandbox 实现，WebAssembly Sandbox 作为高性能选项

### 6.4 治理和运维层

> 这一层是 OryxOS 区别于个人级 Agent OS 的核心差异化所在

- **Web 仪表板**：提供 Web 仪表板做 Profile 管理、Session 查看、监控看板、审计日志查询
- **SSO 和多租户**：SAML/OIDC 接入，对接企业 AD/Okta/Entra ID/阿里云 IDaaS。三级租户模型（组织、部门、项目），RBAC 权限粒度到 Agent、Tool、Skill 级别
- **审计与可追溯**：完整审计事件记录、JSON 结构化输出、trace ID 串联、敏感信息脱敏、SIEM 导出
- **可观测性**：Prometheus 指标、结构化日志、健康检查接口、Grafana Dashboard 模板
- **集群化部署与高可用**：多节点协同通过 Nacos 或 ETCD 完成，节点故障自动迁移负载

### 6.5 企业集成层

- **企业 IT 系统 connector**：ERP（用友、金蝶、SAP）、CRM（销售易、纷享销客、Salesforce）、CMDB、监控系统、内网知识库的现成 connector

---

## 7. 社区共建功能

社区共建功能不在 OryxOS 主线开发计划内，作为长期方向开放给社区贡献，不规定时间表。

- **剩余项目文档**：API 参考文档、部署运维手册、贡献者指南 CONTRIBUTING.md、典型场景使用手册
- **Skills Marketplace**：社区贡献的 Skill 共享平台，兼容 agentskills.io 开放标准，跟 OpenClaw 和 Hermes Agent 兼容
- **SDK 多语言支持**：优先级 Java → Python → TypeScript → Go
- **可视化 Profile 编辑器**：让非工程师也能配置 Agent，接近 Dify 的 Agent 配置界面
- **Native 文件生成**：不依赖 LibreOffice 直接生成 pptx、docx、xlsx（Apache POI）
- **多区域部署**：跨地域 OryxOS 集群，Agent、Memory、Session 可以跨区域协同
- **Kubernetes Operator**：一键部署、声明式配置、GitOps 工作流
- **移动端管理台**：手机随时查集群状态、处理告警
- **Voice Channel**：语音唤醒和连续语音对话
- **RISC-V 和边缘部署**：跑在 Raspberry Pi、边缘网关（GraalVM Native Image）

---

## 8. 非功能需求

### 8.1 性能

| 指标 | 目标 |
|------|------|
| 单节点 Agent 数 | ≥ 10 个 |
| 单节点并发 Session 数 | ≥ 100 个 |
| Session 创建 P99 延迟 | ≤ 200ms |
| OryxOS 内部转发开销 | ≤ 50ms |

（LLM 调用本身的延迟取决于 Provider，不在 OryxOS 控制范围内）

### 8.2 可靠性

- 已注册的 Profile 配置和已写入的 Session 数据保证不丢
- LLM Provider 故障时核心阶段直接报错，完整 failover 在扩展阶段实现
- Tool 调用失败时按重试策略再调，默认指数退避最多三次

### 8.3 可运维性

- 配置变更通过 Profile YAML 文件修改，核心阶段重启服务生效
- 支持物理机、虚拟机、Docker、Kubernetes 部署

### 8.4 兼容性

- **JDK**：21 及以上（Spring Boot 3.x 要求）
- **操作系统**：Linux 主流发行版（Ubuntu 22.04+、CentOS 8+、Debian 11+、Alibaba Cloud Linux 3、Rocky Linux）
- **LLM 协议**：OpenAI 兼容协议是事实标准，只要 Provider 实现这套协议，OryxOS 就能直接接

### 8.5 安全

- API 调用支持 HTTPS
- 敏感配置（LLM API key、数据库密码、Tool 凭证）支持加密存储，不能明文写在配置文件里
- Tool 调用通过应用层白名单校验做基础隔离
- 完整的鉴权机制、Docker Sandbox 隔离、SSO 集成放在扩展阶段

### 8.6 合规

- **数据驻留**：OryxOS 不主动外发任何数据，所有数据留在企业自己的基础设施上
- 完整的审计日志覆盖、SIEM 导出、SOC 2、GDPR、HIPAA、等保三级的对接放在扩展阶段

---

## 9. 关键流程

### 流程一：工作区初始化

```
用户执行 oryxos init
  → OryxOS 创建 .oryxos/ 目录及五个子目录
  → 创建三个 Bootstrap 文件（AGENTS.md、SOUL.md、USER.md）
  → 生成默认 Profile（profiles/default.yaml）
用户编辑 Bootstrap 文件填入项目背景、Agent 人格、用户偏好
用户编辑 default.yaml 配置 LLM Provider 的 API key 和模型
```

### 流程二：Profile 创建和 Agent 启动

```
用户执行 oryxos profile create <name>
  → OryxOS 在 .oryxos/profiles/ 下创建新 YAML 文件
用户编辑配置 Agent 人格、Provider、Tool 列表、Channel
用户执行 oryxos chat --profile <name>
  → OryxOS 加载 Profile
  → 初始化 Provider 连接
  → 注册 Tool 到 Agent 工具池
  → 把 Bootstrap 文件加载到系统提示词
  → Agent 进入待对话状态
```

### 流程三：消息处理（最高频链路）

```
消息从 Channel 进来（CLI 输入或 HTTP API 调用）
  → Channel Adapter 转换成内部统一格式
  → Agent 查询 Session 上下文
  → 组装 LLM Prompt（Bootstrap + 对话历史 + 可用 Tool 列表）
  → 调用 LLM Provider 获取响应
  → [有 Tool 调用] → OryxOS 执行 Tool → 结果回传给 LLM 继续生成
  → 最终响应通过 Channel Adapter 发回给用户
  → 所有动作落结构化日志
```

### 流程四：Tool 调用

```
LLM 通过 Function Calling 指明 Tool 名称和参数
  → OryxOS 从 Agent 工具池找到对应 Tool
  → 做参数校验和白名单校验
  → 内置 Tool：在 OryxOS 进程内执行（白名单约束下）
  → MCP Tool：通过 MCP 协议转发给对应 MCP server 执行
  → 执行结果（成功/失败、错误信息、可重试标识）回传给 Agent
  → Agent 把 Tool 结果作为新一轮 LLM 输入继续推理
```

### 流程五：Session 上下文管理

```
用户第一次跟 Agent 说话
  → OryxOS 用 Channel+用户+Agent 联合 ID 查活跃 Session
  → [无活跃 Session] → 创建新 Session，初始化空对话历史
  → [有活跃 Session] → 恢复 Session 上下文
后续消息追加到 Session 对话历史
  → [上下文超限] → 截断早期对话，保留近期（扩展阶段做总结压缩）
Session 超时无消息 → 结束，对话历史归档可查
```

---

## 10. 数据模型

### Profile（YAML 文件）

见 [5.2 Profile 配置](#52-profile-配置)。

### Session（持久化到 SQLite）

| 字段 | 类型 | 说明 |
|------|------|------|
| `session_id` | VARCHAR | 主键，channel+user+profile 联合生成 |
| `profile_name` | VARCHAR | 关联的 Profile 名称 |
| `channel` | VARCHAR | 接入渠道 |
| `user_id` | VARCHAR | 用户标识 |
| `messages_json` | TEXT | JSON 序列化的对话历史 |
| `status` | VARCHAR | `active` / `archived` |
| `created_at` | TIMESTAMP | 创建时间 |
| `last_active_at` | TIMESTAMP | 最后活跃时间 |
| `archived_at` | TIMESTAMP | 归档时间（可空） |

### Memory（文件形态，非数据库表）

长期记忆是 `.oryxos/memory/MEMORY.md` 一个 Markdown 文件，按追加方式写入，无结构化 schema。扩展阶段引入向量库后，Memory 才有结构化的 embedding 存储。

### Tool Invocation（记录每次 Tool 调用）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT | 主键 |
| `session_id` | VARCHAR | 关联 Session |
| `tool_name` | VARCHAR | Tool 名称 |
| `input_json` | TEXT | 调用参数（JSON） |
| `result_json` | TEXT | 执行结果（JSON） |
| `success` | BOOLEAN | 是否成功 |
| `error_message` | TEXT | 错误信息（可空） |
| `duration_ms` | BIGINT | 执行耗时（毫秒） |
| `created_at` | TIMESTAMP | 调用时间 |

### LLM Call（记录每次 LLM 调用）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT | 主键 |
| `session_id` | VARCHAR | 关联 Session |
| `provider` | VARCHAR | Provider 名称 |
| `model` | VARCHAR | 模型名 |
| `prompt_tokens` | INT | 输入 token 数 |
| `completion_tokens` | INT | 输出 token 数 |
| `total_tokens` | INT | 总 token 数 |
| `duration_ms` | BIGINT | 调用耗时（毫秒） |
| `created_at` | TIMESTAMP | 调用时间 |

---

## 11. 里程碑规划

OryxOS 核心功能的实施按 **4 周节奏**组织，每周 3 小时，合计 12 小时。

| 周次 | 时间投入 | 能力主线 | 可演示成果 |
|------|---------|---------|-----------|
| **第一周** | 3 小时 | 对接 LLM + ReAct 循环（核心能力一+二） | `oryxos chat` 多轮对话，Agent 通过 ReAct 调 HTTP Tool 完成天气查询 |
| **第二周** | 3 小时 | Memory + Tool 体系（核心能力三+四） | Agent 记住用户偏好并在后续对话用到，能调本地文件和外部 MCP server |
| **第三周** | 3 小时 | Web Service（核心能力五） | 外部系统通过 10 个 REST 端点完整调用 OryxOS |
| **第四周** | 3 小时 | 多 Agent 演示 + 工程化收尾 | 多 Agent 并存可用，CLI 完整，Session 跨重启恢复，项目主页可访问 |

### 各周详细实施内容

**第一周**（3 小时）：对接 LLM + ReAct 循环
- `oryxos init` 工作区初始化、Profile YAML 解析
- Provider 抽象（基于 Spring AI Alibaba，先跑通 DeepSeek 或 Kimi）
- ReAct 循环（核心循环约数十行 Java，含 LLM 调用、Tool 调用解析、消息累积）
- 一个基础内置 Tool（HTTP）、CLI Channel
- Session 管理（内存版，第四周加 SQLite 持久化）

**第二周**（3 小时）：Memory + Tool 体系
- Memory 长期记忆极简版（MEMORY.md 文件、`save_memory` 和 `recall_memory` 两个内置 Tool、启动时整个文件注入 system prompt）
- 文件操作 Tool（read_file、write_file、list_dir）、Shell Tool（带白名单校验）
- MCP Client 集成（连接外部 MCP server）

**第三周**（3 小时）：Web Service + API 端点
- Web Service 核心 10 个 REST 端点（会话管理 4 个、Agent 调用 1 个、Profile/Memory/Tool 列表 3 个、health/info 2 个）
- 通过 `oryxos serve` 启动 Spring MVC 服务
- 配置与密钥加载（环境变量注入 + 基础校验）

**第四周**（3 小时）：多 Agent 演示 + 工程化收尾
- 多 Agent 演示（配置两个不同 Profile 的 Agent 在同一实例并存）
- 命令行工具完整 12 个命令、Session 持久化到 SQLite（跨重启恢复）
- Bootstrap 文件机制（AGENTS.md、SOUL.md、USER.md 加载到系统提示词）
- 结构化日志、项目主页（VitePress 或类似静态站点工具）

---

## 12. 风险与未决事项

### 已识别风险

| 风险 | 描述 | 应对措施 |
|------|------|---------|
| **核心功能范围风险** | 4 周 12 小时是极紧的时间约束，某些功能可能比预期复杂 | 核心功能范围卡紧；某周完不成时立刻把末段功能挪到扩展功能，保证每周有可演示成果 |
| **Spring AI 兼容性风险** | Function Calling、Stream、Token 计数、错误码细节不一致 | 核心阶段先把 OpenAI 协议跑稳，其他 Provider 在扩展阶段做完整回归测试 |
| **Tool 执行安全风险** | 应用层白名单不是完整 Sandbox，可能影响 OryxOS 进程 | 严格限制内置 Tool 能力范围，核心阶段不建议在生产环境跑高敏感场景 |
| **Java 启动速度和内存占用** | Java 应用启动慢、内存占用大，影响体验 | 核心阶段先验证功能完整，扩展阶段引入 GraalVM Native Image |
| **社区接力的不确定性** | 扩展功能依赖社区贡献者，可能某些功能长期没人推进 | 项目维护方对核心扩展功能保持基本投入，社区共建功能靠社区 |
| **定位被误读的风险** | 社区可能问"核心阶段跟 OpenClaw、Hermes 有什么区别" | 文档明确说明核心阶段是地基，差异化是终局，不包装成完整企业级 Agent OS |
| **生态关系风险** | OryxOS 和 OpenClaw、Hermes 的关系 | 通过 SKILL.md 互通，生态互补不竞争；OpenClaw 偏个人、Hermes 偏小团队、OryxOS 定位企业 |

### 未决事项

| 事项 | 说明 | 决议时间 |
|------|------|---------|
| Provider 抽象接口设计 | 直接用 Spring AI `ChatClient`，还是在 `ChatClient` 之上加一层 OryxOS 自己的抽象 | 技术方案阶段 |
| 底层存储选 SQLite 还是 H2 | SQLite 是嵌入式 C 实现，H2 是纯 Java | 技术方案阶段 |
| Bootstrap 文件加载顺序和优先级 | AGENTS.md、SOUL.md、USER.md 怎么组合进系统提示词 | 技术方案阶段 |
| GraalVM Native Image 引入时机 | 核心阶段还是扩展阶段 | 核心阶段结束后 |

---

## 13. 验收标准

### 功能验收

核心功能（第 5 章）全部完成，每个功能模块至少有一个端到端测试用例覆盖：

- [ ] `oryxos init` 工作区初始化
- [ ] Profile 配置和管理（支持多 Profile 并存）
- [ ] Provider 抽象（至少跑通 DeepSeek 和 Kimi 两个）
- [ ] ReAct 循环（多轮 Tool 调用、正确累积消息历史、达到最大迭代次数时正确终止）
- [ ] Memory 长期记忆（save_memory 写入、recall_memory 关键词检索、启动时注入 system prompt）
- [ ] 内置 Tool（文件、HTTP、Shell、save_memory、recall_memory、notify）
- [ ] Plugin Tool 接入（方式一零代码 SKILL.md + MCP 跑通；方式三 @Tool 注解示例跑通）
- [ ] MCP Client 集成、CLI Channel
- [ ] 定时任务 `AgentScheduler`（第三触发源，cron 到点自动触发，跟 CLI/Web Service 复用同一条 `AgentService` 链路）
- [ ] Web Service 核心 10 个 REST 端点全部跑通
- [ ] Session 持久化（SQLite，跨重启恢复）
- [ ] 12 个命令行工具
- [ ] 配置与密钥加载

### 性能验收

通过压力测试验证：
- 单节点 10 个 Agent 稳定运行 4 小时
- 单节点 100 个并发 Session
- Session 创建 P99 延迟 < 200ms
- 内部转发开销 < 50ms

### 可运维性验收

- 完整的部署文档（新手 30 分钟内完成单节点部署）
- 命令行工具有清晰的帮助和错误提示
- 项目主页可访问，讲清楚 OryxOS 是什么、怎么快速开始

### 场景验收（两个 Demo）

早期按"一个 Demo 验证一个能力"拆了五个 Demo，但真实场景从来不是单一能力独立跑的——一个能打动人的 Agent，一定是多个能力叠在一起、自己到点跑起来的。改成两个**每日自动运行**的端到端 Demo，每个 Demo 横向串起多个核心能力，两个 Demo 加起来覆盖全部五大核心能力加定时任务这个第三触发源。两个 Demo 跑通是核心功能发布的**硬条件**：

| Demo | 验证能力 | 场景描述 | 验收标准 |
|------|---------|---------|---------|
| **Demo 一：每日天气** | 能力一+二（LLM + ReAct）、能力四（内置 HTTP Tool）、定时任务（`AgentScheduler`） | 每天早上到点自动查天气、生成穿搭建议，推送到企业 IM 群 | 不需要人工触发，到点自动跑完整 ReAct 循环；查天气和推送各一次 HTTP 调用，都过 Sandbox 域名白名单且都写入 `tool_invocations`；`GET /api/v1/sessions/{id}` 能查到这次自动触发的完整对话记录 |
| **Demo 二：每日科技日报** | 能力四（Plugin Tool 方式一 SKILL.md 零代码 + 方式二 MCP）、能力三（Memory）、定时任务（`AgentScheduler`） | 每天到点自动汇总当日科技新闻并推送，且日报内容会体现用户之前说过的关注方向（比如"更关注 AI 和芯片"） | 业务方全程不写 Java 代码，只写 SKILL.md + `mcp_servers.yaml` + Profile 的 `schedules` 字段；LLM 自己决定调新闻 MCP 工具、自己组织日报、自己调推送 MCP 工具，OryxOS 不解析任务步骤；日报内容能体现 `MEMORY.md` 里记住的偏好 |

两个 Demo 都是"钟推"（`AgentScheduler` 到点自动触发），但都要能同时支持"人推"手动补跑一次做验证（`oryxos chat` 或 `POST /agents/{name}/invoke`），验证同一个 Agent 不管从哪个入口触发，走的都是同一条 `AgentService` 链路。

---

## 14. 总结

OryxOS 是基于 Java 实现的面向企业场景的 Agent OS，装在企业自己的 K8s 或服务器上，作为统一底座跑各种业务 Agent，共享一套渠道接入、模型路由、工具调用、记忆系统、沙箱执行能力。

**OryxOS 的交付分两段：**

1. 核心阶段先用 Java 把 Agent OS 的运行时内核做扎实，这一层在能力上对齐业界开源 Agent OS 的基础层
2. OryxOS 真正的差异化治理层（多租户、SSO、完整审计、Tool 治理），在核心内核之上由扩展阶段和社区共建陆续补齐。**核心阶段是地基，企业级治理是终局。**

**核心阶段五大核心能力：**

- **对接 LLM**：Provider 抽象，让 Agent 能调任意主流大模型，运行时切换无 lock-in
- **ReAct 循环**：Agent 大脑，LLM 思考 + 工具执行，多步骤任务自主完成
- **Memory 三层记忆**：核心阶段会话 + 长期 MEMORY.md，跨对话记住用户偏好和项目背景
- **Plugin 自定义工具 + 内置工具集**：内置文件/Shell/HTTP，业务方通过 SKILL.md + MCP 零代码扩展、MCP server 轻代码扩展、@Tool 注解重代码扩展
- **Web Service**：REST API 覆盖会话管理、Agent 调用、Profile/Memory/Tool 信息查询、系统状态

**核心理念**：OryxOS 五大能力扎实落地，业务方组合 SKILL.md + MCP server 就能解决业务问题，通过 Web Service 接入已有系统，不需要写 Agent 后端代码。OryxOS 不绑定具体业务，业务方按自己的需求组合。
