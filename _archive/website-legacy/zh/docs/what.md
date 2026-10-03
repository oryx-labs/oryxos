# OryxOS 是什么

***一个 Java 原生的 Agent OS——在你自己的基础设施上运行和管理一批业务 Agent，共享渠道接入、LLM 路由、记忆系统和工具体系，全部打包在一个可部署的二进制文件里。***

![OryxOS Architecture](/images/architecture.svg)

## 它是什么

OryxOS 是一个基于 Spring Boot 3.x、运行在 JDK 21 上的统一 Agent 平台，专为企业私有化部署设计。你只需写一份 YAML Profile 来定义一个 Agent——它的身份、对话的 LLM、可用的工具、共享的记忆。其余的事情 OryxOS 全包：推理循环、上下文组装、工具执行、沙箱管控、会话持久化、REST API 对外暴露。多个 Agent 可以在同一个实例里并发运行。业务系统通过 HTTP 接入。数据始终在你自己的基础设施上。

## Agent OS vs Agent Runtime

这两个概念经常被混用，但它们描述的是本质不同的两个层次。

| | Agent Runtime | Agent OS |
| --- | --- | --- |
| 范围 | 单个 Agent | 一批 Agent |
| 管理内容 | 推理循环、上下文、工具执行 | 生命周期、渠道接入、记忆系统、治理 |
| 入口 | 库调用或框架调用 | 带 REST API 的可部署二进制 |
| 多 Agent | 不在范围内 | 一等公民：多 Profile、共享能力 |
| 类比 | 进程执行环境 | 进程之上的操作系统层 |

Runtime 让一个 Agent 跑起来。Agent OS 让一批 Agent 跑起来并统一管理。

OryxOS 内部包含一个 Runtime（自实现的 ReAct Loop），但定位是其上的 OS 层：统一的渠道接入、共享记忆、集中式 Tool 注册表、可审计的调用记录，以及任何语言都能调用的 REST API。

## 五项核心能力

### LLM 路由

对主流模型的 Provider 抽象：DeepSeek、通义、Kimi、智谱、混元、豆包、Anthropic、OpenAI，以及任何兼容 OpenAI 协议的端点。Agent 与 Provider 解耦——Profile 声明用哪个 Provider，Agent 本身感知不到背后是哪家厂商。切换 Provider 只改配置，不动代码。多 Provider 并存通过显式的名称到 `ChatModel` 映射实现，而不是 Bean 扫描。支持通过 Ollama 或 vLLM 接入本地推理。

### ReAct Loop

自实现的推理引擎，不依赖任何外部 Agent 框架封装。每次迭代：组装 Prompt（系统提示词 + Bootstrap 上下文 + 长期记忆 + 对话历史 + 可用工具列表），调用 LLM，检查响应中是否有工具调用，执行工具，把结果追加到历史，然后继续循环。循环持续到 LLM 给出最终答案，或达到配置的最大迭代次数为止。整个循环只有数十行 Java，完全透明可检查。Spring AI 只用于 LLM 协议转换，其自动工具执行功能被显式禁用。

### 记忆系统

核心阶段提供两层记忆。会话记忆保存当前对话历史，持久化到 SQLite，重启后可恢复。长期记忆存储在 `MEMORY.md`——一个 Markdown 文件，Agent 通过 `save_memory` 写入、通过 `recall_memory` 关键词检索。每次构建 Prompt 时，该文件全文都会被注入，让 Agent 跨对话保持持续的上下文。文件超过 4000 字符时自动截断以控制 context 占用。向量检索是扩展阶段的升级路径。

### 工具体系

内置工具覆盖基础场景：`read_file`、`write_file`、`list_dir`、`shell`、`http_get`、`http_post`、`save_memory`、`recall_memory`。所有工具执行都有沙箱管控——文件操作有路径白名单，Shell 有命令白名单，HTTP 有域名白名单。

扩展按三档进行，按工作量从低到高排列：

| 档位 | 工作量 | 方式 |
| --- | --- | --- |
| 零代码 | 最低 | 写一份 `SKILL.md` 描述任务，在 Profile 中引用社区现有的 MCP server |
| 轻代码 | 中等 | 用任意语言写一个 MCP server，OryxOS 作为 MCP Client 接入 |
| 重代码 | 最高 | 用 `@Tool` 注解标注 Spring Bean，直接在进程内注册 |

所有工具——内置的、MCP 对接的、原生的——都通过 `ToolRegistry` 注册，向 ReAct Loop 暴露统一的 `OryxTool` 接口。

### REST API

`/api/v1` 下的十个 REST 端点对外暴露所有能力：会话生命周期管理、无状态 Agent 调用、Profile 列表查询、记忆查看、工具清单、健康检查和运行时信息。任何能发 HTTP 请求的语言都能接入，核心阶段不需要 SDK。Web Service 是集成边界——业务系统在这里接入，而不是在库层面。

## 设计原则

- **平台优先于 Agent**——最重要的交付物是让任何 Agent 可靠运行的环境，而不是某个具体的 Agent
- **核心自实现，管道复用**——推理循环手写；LLM 协议适配委托给 Spring AI Alibaba
- **配置即 Agent**——一个 Agent 完全由一份 YAML Profile 定义，不需要写代码
- **开放标准**——工具用 MCP，Agent 间协作用 A2A，技能用 `SKILL.md` 文件
- **实例无状态，状态外置**——这是未来走向分布式架构而不需要大改设计的前提
- **安全是基础，不是事后补丁**——工具来源管控、最小权限、强制沙箱白名单、凭证走环境变量、完整审计记录从第一天就写入 SQLite
- **分阶段、有节制**——先构建最小完整的运行时内核；治理和分布式基础设施在真实使用数据验证后再做
