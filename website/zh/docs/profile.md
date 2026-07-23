# Profile 配置（AGENT.md frontmatter）

在 OryxOS 里，**一个目录 = 一个 Agent**。一个 Agent 就是 `.oryxos/agents/<name>/` 目录下由 `AGENT.md` 定义的实体。该文件由两部分组成：

- **YAML frontmatter** —— 这个 Agent 的 *Profile*：它是谁、用哪个 Provider/模型、能调哪些工具、有哪些定时任务、以及对话行为上限。
- **Markdown 正文** —— 这个 Agent 的任务指令，注入 system prompt。

**不再有 `.oryxos/profiles/` 目录**。Profile 就是 frontmatter，不是单独的文件。可选的 `skills/*.md`、`scripts/`、`REFERENCE.md` 放在同一目录，由正文用 `read_file` / `shell` 按需加载——它们**不在** frontmatter 里声明。

Agent 可以通过 `/api/v1/agents` 接口和 Web 管理台**动态**创建与编辑——在线写入/修改 `AGENT.md` 并重新注册，无需重启。

---

## 完整示例

`.oryxos/agents/ops-agent/AGENT.md`：

```markdown
---
name: ops-agent
description: 运维助手，负责部署和监控任务

identity:
  agent_name: 运维小欧
  prompt: |
    你是一个专业的运维助手。
    回答简洁，优先给可执行的 shell 命令，而不是长篇解释。

provider:
  name: deepseek          # 对应已注册的 Provider 名称
  model: deepseek-chat
  temperature: 0.7
  api_key: ${DEEPSEEK_API_KEY}

tools:
  - read_file
  - shell
  - http_get
  - save_memory
  - recall_memory
  - notify

mcp_servers:
  - github-mcp

channels:
  - name: cli

bootstrap:
  - AGENTS.md
  - SOUL.md
  - USER.md

schedules:
  - id: morning-check
    cron: "0 0 8 * * *"
    zone: Asia/Shanghai
    message: 执行早晨健康检查。

settings:
  max_iterations: 10
  max_history_turns: 20
---

你是一个专业的运维助手。被触发时检查磁盘和内存占用，汇总异常项，并把汇总
发到名为 `ops-lark` 的通知渠道。
```

闭合的 `---` 之下是**正文**：它作为这个 Agent 的任务指令，与 bootstrap 文件一起注入 system prompt。

---

## frontmatter 字段

### 顶层字段

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `name` | string | 是 | Agent 唯一标识，与目录名一致，用于 CLI（`--profile <name>`）和 API 路径（`/api/v1/agents/{name}`） |
| `description` | string | 否 | 人类可读的描述，展示在管理台和 `oryxos profile list` |
| `identity` | object | 是 | Agent 的名字和核心 prompt，见 [Identity](#identity) |
| `provider` | object | 是 | LLM Provider 和模型选择，见 [Provider](#provider) |
| `tools` | list | 否 | 该 Agent 可用的工具名列表，必须已在 `ToolRegistry` 注册，默认无工具 |
| `mcp_servers` | list | 否 | 接入的 MCP server 名列表（来自 `mcp_servers.yaml`），其暴露的工具对该 Agent 可用 |
| `channels` | list | 否 | 接入渠道，核心阶段支持 `cli` |
| `bootstrap` | list | 否 | 按序注入 system prompt 的 Bootstrap 文件，从工作区根解析 |
| `schedules` | list | 否 | 该 Agent 的定时触发任务，见 [Schedules](#schedules) |
| `settings` | object | 否 | 行为参数，见 [Settings](#settings) |

### Identity

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `identity.agent_name` | string | 是 | Agent 的展示名，出现在 CLI 提示符和审计记录里 |
| `identity.prompt` | string | 是 | Agent 的核心 system prompt，最先注入——在 bootstrap 文件、正文和记忆之前 |

### Provider

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `provider.name` | string | 是 | 映射到已注册 Provider 的 key，从 Provider 注册表（SQLite）动态解析，见 [Provider 配置](#provider-配置) |
| `provider.model` | string | 是 | 传给 Provider API 的模型标识 |
| `provider.temperature` | float | 否 | 采样温度，默认取 Provider 的配置默认值 |
| `provider.api_key` | string | 否 | Provider 的 API Key，用环境变量占位符（`${DEEPSEEK_API_KEY}`），不得写死明文 |

---

## Provider 配置

`provider.name` 按名引用一个 Provider。OryxOS 维护显式的 `name → ChatModel` 映射（宪法三——绝不靠 Spring Bean 类型扫描），但该映射是**运行时可变**的：Provider 存于 SQLite 的 `providers` 表，按名动态解析。

启动时从 `config/application.yml`（`oryxos.providers` 列表）播种 Provider，之后通过管理台或 `/api/v1/providers` 接口动态管理：

```yaml
# config/application.yml
oryxos:
  providers:
    - name: deepseek
      api-key: ${DEEPSEEK_API_KEY}
      base-url: https://api.deepseek.com
    - name: mock                     # 内置假模型——无需 key/url
```

**API Key 规则：** 必须通过环境变量引用，不得写死明文。

```bash
export DEEPSEEK_API_KEY=sk-...
```

若 Profile 的 `provider.name` 在注册表中找不到对应 Provider，该 Agent 会以清晰的错误失败。

---

## Tools

`tools` 字段列出该 Agent 在 ReAct Loop 中可调用的内置工具和 MCP 工具。只有列表里的工具才会作为可调用函数传给 LLM。

内置工具名：

| 名称 | 作用 |
| --- | --- |
| `read_file` | 读文件（路径白名单） |
| `write_file` | 写文件（路径白名单） |
| `list_dir` | 列目录（路径白名单） |
| `shell` | 执行 shell 命令（命令白名单） |
| `http_get` | HTTP GET（域名白名单） |
| `http_post` | HTTP POST（域名白名单） |
| `save_memory` | 向 `MEMORY.md` 追加记忆 |
| `recall_memory` | 关键词检索 `MEMORY.md` |
| `notify` | 向按名引用的通知渠道推送消息 |

来自 `mcp_servers` 的 MCP 工具以 server 声明的名称暴露（如 `github_create_pr`）。运行 `oryxos tool list` 查看所有已注册名称。详见 [Tool 体系](/zh/docs/tool)，包括 `notify` 如何按名解析渠道。

### 子指令与脚本（无 frontmatter 字段）

可复用的指令和辅助脚本放在 **Agent 目录内**，不在 frontmatter 里：

```text
.oryxos/agents/ops-agent/
├── AGENT.md            # frontmatter（Profile）+ 正文（任务指令）
├── skills/
│   └── deploy-runbook.md
├── scripts/
│   └── collect_metrics.py
└── REFERENCE.md
```

正文指引 Agent 何时取用它们——`skills/*.md` 和 `REFERENCE.md` 用 `read_file`，`scripts/` 用 `shell`。这是**一个 Agent 内部**的渐进式披露：没有全局能力索引，也没有单独的 `skills` frontmatter 字段。

---

## Bootstrap 文件

Bootstrap 文件按列出的顺序注入 system prompt，提供跨所有 Agent 共享的工作区级上下文。

| 文件 | 谁来写 | 用途 |
| --- | --- | --- |
| `AGENTS.md` | 项目维护者 | 项目级约定：Agent 行为边界、命名、协作规则 |
| `SOUL.md` | 项目维护者 | Agent 人格定义：语气、风格、价值观 |
| `USER.md` | 仅用户手写 | 用户偏好与背景：Agent 只读，绝不写入 |

从 OryxOS 视角，`USER.md` 严格只读。可被 Agent 写入的对应文件是 `MEMORY.md`，通过 `save_memory` 工具更新。与该 Agent 无关的文件可从列表中省略。

---

## Schedules

`schedules` 声明该 Agent 的定时触发任务。每条到点时以 Agent 正文作为任务触发一次。定时任务也可通过 `/api/v1/schedules` 接口和管理台列出、立即运行、启用/停用。

```yaml
schedules:
  - id: morning-check
    cron: "0 0 8 * * *"     # Spring cron：秒 分 时 日 月 周
    zone: Asia/Shanghai
    message: 执行早晨健康检查。
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | string | 该 Agent 内此定时任务的唯一 id |
| `cron` | string | Spring 6 段 cron 表达式 |
| `zone` | string | IANA 时区（如 `Asia/Shanghai`） |
| `message` | string | 定时触发时交给 Agent 的触发消息 |

---

## Settings

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `max_iterations` | `10` | 每条用户消息的 ReAct Loop 最大迭代次数，防止无限工具循环。达到上限后强制返回当前部分结果。 |
| `max_history_turns` | `20` | 注入 Prompt 的最近对话轮数。超出部分从最旧的消息开始丢弃，控制 context window。一轮 = 一条用户消息 + 一条助手回复。 |

省略 `settings` 时，两个值都取默认。
