# OryxOS 是什么

***OryxOS 是面向企业 AI 应用的开源 Agent Harness OS。应用带来自己的业务规则、数据、系统和用户体验；OryxOS 为 Agent 与 Task 提供共享 Runtime 和治理底座。***

![企业 AI 应用连接 OryxOS Agent Runtime 与治理底座](/images/runtime-governance-zh.svg)

![OryxOS Architecture](/images/architecture.svg)

## 这条路我们走到了哪

我们将目标平台模型与当前 Runtime 已具备的能力分开说明：

- **当前可用**：通过**一句话**或一个 Markdown 目录定义 Agent，并在自己的基础设施上使用模型 Provider、Memory、Knowledge、内置 Tool、MCP、Skill、通知、调度、沙箱控制、审计记录、REST API 和 Web Manager。
- **演进中**：让持久、受治理的 **Task** 成为企业 AI 应用的共同执行单元，持续建设 Checkpoint/Recovery、审批、策略、成本、评测、分布式 Runtime 和 Control Plane 能力。
- **探索中**：在多个应用之间优化模型、能力、质量、成本和风险，支持受治理的多 Agent 协作与更广泛的生态互操作。参见[路线图](./roadmap)。

每一次有实际影响的 AI 行动都应遵循一致的执行链路：

```text
Task -> Identity -> Authorization -> Policy -> Approval
     -> Model / Tool Execution -> Audit / Trace / Cost
     -> Artifact / State Update
```

这是项目的目标方向，各项能力会分阶段交付，并在文档中明确标注状态。

## Agent 公式

一个 Agent 需要的一切能力都**内置在 OS 里**，Agent 本身只是一份按名引用这些能力的意图声明：

![自然语言 + Memory + Tool + MCP + Skill + 知识库 + Notify = 一个能干活的 Agent](/images/agent-formula-zh.svg)

这是 OryxOS 的务实起点：**把「定义一个 Agent」的成本压到趋近于零。** 你写想要什么，Runtime 负责供给能力、安全边界和执行证据。

具体形态是 **一个目录 = 一个 Agent**：`.oryxos/agents/<name>/` 下的一个目录，一份 `AGENT.md`——YAML frontmatter 是这个 Agent 的 profile（身份、对话的模型、引用哪些工具 / Skill），正文是任务指令。目录放进去就上线。通过 REST API 或 Web 管理台在运行时创建、编辑、删除 Agent——包括用一句话生成一个完整的 Agent。业务系统走 HTTP 接入。数据始终不出你的基础设施。

## 什么是 agent harness（以及为什么是 OS）

**Agent harness（运行骨架）**是套在模型外面、把模型变成能干活的 Agent 的那层脚手架：驱动 reason → act → observe 的循环、它能调用的工具与执行工具的机制、每次 LLM 调用前组装好的上下文、它积累的记忆、约束它的沙箱、记录它做过什么的审计。裸模型只会生成文本——harness 才让它可靠、安全地『做事』。

企业需要运行多个 Agent，但平台也必须让每个应用共享同一套运行和治理语义。OryxOS 是一个 **Agent Harness OS**：给每个 Agent 一致的 Harness，并在多个应用之间组织 Task。三层看——**模型 → Harness → OS**：

| | 裸模型 | Agent Harness | Agent Harness OS |
| --- | --- | --- | --- |
| 范围 | 单次 LLM 调用 | 单个可靠的 Agent | 企业应用及其 Task |
| 提供什么 | 文本生成 | 循环、工具 + 执行、上下文、记忆、沙箱、审计、投递 | 生命周期、渠道接入、路由、共享注册表、调度、治理、管理台 + API |
| 入口 | 对模型的一次 API 调用 | 一次库调用或框架调用 | 带 REST API 的可部署二进制 |
| 多 Agent | 不在范围内 | 不在范围内 | 建立在共享 Task 与治理之上的演进能力 |
| 类比 | 一条 CPU 指令 | 一个带运行时的进程 | 运行一群进程的操作系统 |

模型生成文本。Harness 把一个模型变成一个真正能干活的 Agent。Harness **OS** 为每个 Agent 和应用提供统一的执行、能力与治理语义；多 Agent 团队是建立在这套底座之上的演进用例。

## 五项核心能力

### LLM 路由

对主流模型的 Provider 抽象：DeepSeek、通义、Kimi、智谱、混元、豆包、Anthropic、OpenAI，以及任何兼容 OpenAI 协议的端点。Agent 与 Provider 解耦——Agent 按名字声明用哪个 Provider，感知不到背后是哪家厂商。Provider 存放在动态注册表里，支持完整 CRUD（REST 或管理台）——首次启动从配置播种，之后以数据库为准，运行时新增或换密钥都不用重启。多 Provider 通过显式的名称到模型路由并存；支持通过 Ollama 或 vLLM 接入本地推理。

### ReAct Loop

自实现的推理引擎，不被任何外部 Agent 框架包裹。每次迭代：组装 Prompt（系统提示词 + Bootstrap 上下文 + 长期记忆 + 对话历史 + 可用工具列表），调用 LLM，检查响应中的工具调用，在沙箱管控下执行，把结果以结构化的工具消息回填，继续循环——直到给出最终答案或达到迭代上限。整个循环只有数十行、完全透明可检查；各家厂商的协议差异由适配层吸收，而工具执行始终由 OryxOS 自己掌控。

### 记忆系统

当前两层。会话记忆保存对话历史，持久化、重启可恢复。长期记忆**按 Agent 隔离**——每个 Agent 通过 `save_memory` 写入自己的 `MEMORY.md`、通过 `recall_memory` 检索；每次触发还会自动记入它的归档记忆。记忆注入每次系统提示词，让 Agent 跨对话保持上下文。升级路径——语义化的向量记忆 + 内置知识库——是[路线图](./roadmap)上的头部项。

### 工具体系

**24 个内置工具**覆盖基础场景：文件操作（读 / 写 / 列目录 / 追加 / 移动 / 复制 / 删除 / 建目录）、Shell、HTTP（get / post / 任意请求 / 抓网页 / 下载）、时间与 JSON 工具、记忆，以及 `notify`。全部在沙箱管控下执行——文件走路径白名单、Shell 走命令白名单、HTTP **读写分治**（读：默认放行 + SSRF 黑名单；写：域名通配符白名单）——每次调用都有审计。`notify` 按名字推送到通知渠道（飞书 / 企业微信 / 钉钉 / Slack / Discord / Telegram / WhatsApp / Teams / Google Chat / Mattermost / Matrix / 通用 webhook），渠道本身也是支持完整 CRUD 的动态注册表。

扩展按三档进行，按工作量从低到高：

| 档位 | 工作量 | 方式 |
| --- | --- | --- |
| 零代码 | 最低 | 写一份 `SKILL.md` 描述任务，引用社区现有的 MCP server |
| 轻代码 | 中等 | 用任意语言写一个 MCP server，OryxOS 作为 MCP Client 接入 |
| 重代码 | 最高 | 写一个原生进程内工具，直接注册 |

所有工具——内置的、MCP 对接的、原生的——都经由同一个 `ToolRegistry` 注册，向 ReAct Loop 暴露统一接口。

### REST API 与 Web 管理台

`/api/v1` 下的 REST 端点对外暴露所有能力：动态 Agent 生命周期（创建 / 列表 / 查询 / 更新 / 删除 / 调用，以及按 Agent 的记忆、控制台会话、一句话生成）、会话、Provider 与通知渠道 CRUD、定时任务、沙箱白名单管理、工作区文件浏览、工具清单、健康检查和运行时信息——统一响应信封。任何能发 HTTP 的语言都能接入，不需要 SDK。

其上是部署在 `/admin/` 的 Web 管理台：概览面板、带一句话创建流程的 Agent 管理、支持 Markdown 渲染的文件浏览器、Skill、定时任务、会话、Provider、通知渠道和沙箱白名单。

## 设计原则

- **平台优先于 Agent**——最重要的交付物是让任何 Agent 可靠运行的环境，而不是某个具体的 Agent
- **核心自实现，管道复用**——推理循环手写；各厂商协议适配委托给成熟的适配层
- **一个目录 = 一个 Agent**——一个 Agent 由一个 markdown 目录定义，不需要写代码
- **开放标准**——工具用 MCP，Agent 间协作用 A2A，技能用开放格式
- **实例无状态，状态外置**——未来走向分布式而不需要大改设计的前提
- **安全是基础，不是事后补丁**——最小权限、强制沙箱白名单、凭证走环境变量、完整审计从第一天就持久化
- **分阶段、有节制**——先构建最小完整的运行时内核；治理和分布式基础设施在真实使用验证后再上
