# OryxOS 是什么

***一个 Java 原生的 Agent Harness OS——给每个 Agent 一套生产级 harness，并在你自己的基础设施上运行和管理一整队 Agent，共享渠道接入、LLM 路由、记忆系统、工具体系和可审计执行，全部打包在一个可部署的二进制文件里。***

![OryxOS Architecture](/images/architecture.svg)

## 什么是 agent harness

**Agent harness（运行骨架）**是套在模型外面、把模型变成能干活的 Agent 的那层脚手架：驱动 reason → act → observe 的循环、它能调用的工具与执行工具的机制、每次 LLM 调用前组装好的上下文、它积累的记忆、约束它的沙箱、记录它做过什么的审计。裸模型只会生成文本——harness 才让它可靠、安全地『做事』。

OryxOS 是一个 **Agent Harness OS**：给每个 Agent 同一套生产级 harness，并像操作系统调度进程一样运行一整队 Agent。可以把它理解成三层——**模型 → Harness → OS**：模型是大脑；harness 是让单个 Agent 真正跑得起来的那层；OS 则是把一群带 harness 的 Agent 当作共享平台来运行，在其上再叠加生命周期、渠道接入、路由和治理。

## 它是什么

OryxOS 是一个基于 Spring Boot 3.x、运行在 JDK 21 上的统一 Agent 平台，专为企业私有化部署设计。**一个目录 = 一个 Agent**：`.oryxos/agents/<name>/` 下的一个目录，配一份 `AGENT.md`（YAML frontmatter 作为这个 Agent 的 profile——身份、对话的 LLM、可用的工具——加上一段任务指令正文）就定义了一个 Agent。把目录放进去它就上线，不再有单独的 `profiles/` 目录。其余的事情 OryxOS 全包：推理循环、上下文组装、工具执行、沙箱管控、会话持久化、REST API 对外暴露。多个 Agent 可以在同一个实例里并发运行。你可以通过 REST API 或 Web 管理台在运行时创建、编辑、删除 Agent——包括用一句话生成 `AGENT.md` 草稿。业务系统通过 HTTP 接入。数据始终在你自己的基础设施上。

## 模型、Harness 与 Harness OS

这三个概念经常被混用，但它们描述的是范围本质不同的三个层次。

| | 裸模型 | Agent Harness | Agent Harness OS |
| --- | --- | --- | --- |
| 范围 | 单次 LLM 调用 | 单个可靠的 Agent | 一整队 Agent |
| 提供什么 | 文本生成 | 循环、工具 + 执行、上下文、记忆、沙箱、审计、投递 | 生命周期、渠道接入、路由、共享注册表、调度、治理、管理台 + API |
| 入口 | 对模型的一次 API 调用 | 一次库调用或框架调用 | 带 REST API 的可部署二进制 |
| 多 Agent | 不在范围内 | 不在范围内 | 一等公民：多 Agent、共享能力、运行时生命周期管理 |
| 类比 | 一条 CPU 指令 | 一个带运行时的进程 | 运行一群进程的操作系统 |

模型生成文本。Harness 把一个模型变成一个真正能干活的 Agent。Harness **OS** 则把同一套 harness 交给每个 Agent，并运行整支队伍。

OryxOS **就是**那套 harness——自实现的 ReAct Loop、工具执行、沙箱、上下文组装、按 Agent 隔离的记忆、审计记录——同时它也是其上的 OS 层：统一的渠道接入、共享注册表、可审计的调用记录，以及任何语言都能调用的 REST API。

## 五项核心能力

### LLM 路由

对主流模型的 Provider 抽象：DeepSeek、通义、Kimi、智谱、混元、豆包、Anthropic、OpenAI，以及任何兼容 OpenAI 协议的端点。Agent 与 Provider 解耦——Agent 按名字声明用哪个 Provider，本身感知不到背后是哪家厂商。Provider 存放在一个动态的、以 SQLite 为后端的注册表里，支持完整 CRUD（通过 REST 或管理台创建/编辑/删除）——首次启动时从配置播种，之后以数据库为准，因此运行时新增或换密钥都不用重启。多 Provider 并存通过显式的名称到 `ChatModel` 映射实现，而不是 Bean 扫描；运行时按名字解析并缓存 `ChatModel`。支持通过 Ollama 或 vLLM 接入本地推理。

### ReAct Loop

自实现的推理引擎，不依赖任何外部 Agent 框架封装。每次迭代：组装 Prompt（系统提示词 + Bootstrap 上下文 + 长期记忆 + 对话历史 + 可用工具列表），调用 LLM，检查响应中是否有工具调用，执行工具，把结果以结构化的工具消息回填，然后继续循环。工具调用和结果以结构化的 `tool_call`/`tool_result` 消息（assistant 的工具调用，加上带 id 的工具响应）经 Provider 传递，而不是拍平成纯文本。循环持续到 LLM 给出最终答案，或达到配置的最大迭代次数为止。整个循环只有数十行 Java，完全透明可检查。Spring AI 只用于 LLM 协议转换，其自动工具执行功能被显式禁用，执行完全由 `ToolExecutor` 掌控。

### 记忆系统

核心阶段提供两层记忆。会话记忆保存当前对话历史，持久化到 SQLite，重启后可恢复。长期记忆是**按 Agent 隔离**的——每个 Agent 写入自己的 `.oryxos/agents/<name>/MEMORY.md`（没有 Agent 上下文时回退到全局的 `.oryxos/memory/MEMORY.md`），通过 `save_memory` 写入、通过 `recall_memory` 关键词检索；每次触发某个 Agent 还会自动记入它的归档记忆。每次构建 Prompt 时，该文件全文都会被注入，让 Agent 跨对话保持持续的上下文。文件超过 4000 字符时自动截断以控制 context 占用。向量检索是扩展阶段的升级路径。

### 工具体系

内置工具覆盖基础场景：`read_file`、`write_file`、`list_dir`、`shell`、`http_get`、`http_post`、`save_memory`、`recall_memory`，以及 `notify`。所有工具执行都有沙箱管控——文件操作有路径白名单，Shell 有命令白名单，HTTP 有域名白名单。`notify` 工具按名字推送到一个通知渠道；渠道（飞书 / 企业微信 / 钉钉 / 通用 webhook 适配器）存放在自己独立的、以 SQLite 为后端的动态注册表里，支持完整 CRUD。

扩展按三档进行，按工作量从低到高排列：

| 档位 | 工作量 | 方式 |
| --- | --- | --- |
| 零代码 | 最低 | 写一份 `SKILL.md` 描述任务，在 Profile 中引用社区现有的 MCP server |
| 轻代码 | 中等 | 用任意语言写一个 MCP server，OryxOS 作为 MCP Client 接入 |
| 重代码 | 最高 | 用 `@Tool` 注解标注 Spring Bean，直接在进程内注册 |

所有工具——内置的、MCP 对接的、原生的——都通过 `ToolRegistry` 注册，向 ReAct Loop 暴露统一的 `OryxTool` 接口。

### REST API

`/api/v1` 下的一组 REST 端点对外暴露所有能力：动态 Agent 生命周期（创建 / 列表 / 查询 / 更新 / 删除 / 调用，以及按 Agent 的记忆、控制台会话、一句话文件生成）、会话生命周期管理、Provider 与通知渠道 CRUD、定时任务管理、沙箱白名单管理、工作区文件浏览、Profile 列表查询、工具清单、健康检查和运行时信息。每个响应都包在统一信封里（`{ code, message, data, timestamp }`）。任何能发 HTTP 请求的语言都能接入，核心阶段不需要 SDK。Web Service 是集成边界——业务系统在这里接入，而不是在库层面。

### Web 管理台

一个 Vue 3 + Vite 单页控制台部署在 `/admin/`，风格与本站一致。它在 REST API 之上提供可视化前端：概览面板、带每行"立即触发 / 详情 / 删除"的 Agent 列表和 Agent 详情页（基本信息 / 生成 / 文件 / 会话 / 记忆 标签页，含一句话生成流程）、定时任务管理，以及一个"OS 运行时"分组，覆盖会话、Provider（完整 CRUD）、Tool、Notify 渠道（完整 CRUD）和沙箱白名单。

## 设计原则

- **平台优先于 Agent**——最重要的交付物是让任何 Agent 可靠运行的环境，而不是某个具体的 Agent
- **核心自实现，管道复用**——推理循环手写；LLM 协议适配委托给 Spring AI Alibaba
- **一个目录 = 一个 Agent**——一个 Agent 由一个目录定义（`AGENT.md` = frontmatter profile + 正文指令，加可选的 `skills/`、`scripts/`），不需要写代码
- **开放标准**——工具用 MCP，Agent 间协作用 A2A，技能用 `SKILL.md` 文件
- **实例无状态，状态外置**——这是未来走向分布式架构而不需要大改设计的前提
- **安全是基础，不是事后补丁**——工具来源管控、最小权限、强制沙箱白名单、凭证走环境变量、完整审计记录从第一天就写入 SQLite
- **分阶段、有节制**——先构建最小完整的运行时内核；治理和分布式基础设施在真实使用数据验证后再做
