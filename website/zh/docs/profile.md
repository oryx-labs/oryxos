# Profile 配置

Profile 是定义一个 Agent 的配置文件，存放在 `.oryxos/profiles/` 目录下，每个 Agent 对应一个 YAML 文件。一份 Profile 决定了 Agent 的身份、使用哪个模型、能调用哪些工具、加载哪些技能，以及对话行为的上限。

## 完整示例

```yaml
# Agent 基本信息
name: ops-agent
description: 运维助手

# Agent 身份：注入 system prompt
identity:
  agent_name: 运维小欧
  prompt: 你是一个专业的运维助手，擅长处理 Linux 系统问题、分析日志和排查故障。回答简洁、准确，优先给出可执行的操作步骤。

# LLM Provider 配置
provider:
  name: deepseek          # 对应 application.yml 中 oryxos.providers 的 key
  model: deepseek-chat
  temperature: 0.7

# 可调用的内置 Tool
tools:
  - read_file
  - shell
  - http_get
  - save_memory
  - recall_memory

# 注入 system prompt 的技能文件（非可执行 Tool）
skills:
  - daily-pr-digest       # 对应 .oryxos/skills/daily-pr-digest.md

# 通过 MCP 协议接入的外部 Tool server
mcp_servers:
  - github-mcp            # 对应 .oryxos/mcp_servers.yaml 中的配置项

# 接入渠道
channels:
  - name: cli

# Bootstrap 文件：启动时注入 system prompt
bootstrap:
  - AGENTS.md
  - SOUL.md
  - USER.md

# 行为参数
settings:
  max_iterations: 10      # ReAct Loop 最大迭代次数
  max_history_turns: 20   # 注入上下文的最大对话轮数
```

## 字段说明

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `name` | string | 是 | Profile 唯一标识，与文件名一致 |
| `description` | string | 否 | 人类可读的 Agent 描述 |
| `identity.agent_name` | string | 否 | Agent 的名字，展示在对话界面 |
| `identity.prompt` | string | 是 | 注入 system prompt 的核心身份指令 |
| `provider.name` | string | 是 | Provider 名称，对应 `application.yml` 中的配置 key |
| `provider.model` | string | 是 | 使用的模型名称 |
| `provider.temperature` | float | 否 | 采样温度，默认 `0.7` |
| `tools` | list | 否 | 允许调用的内置 Tool 名称列表 |
| `skills` | list | 否 | 注入 system prompt 的技能文件名列表 |
| `mcp_servers` | list | 否 | 接入的 MCP server 名称列表 |
| `channels` | list | 否 | 接入渠道配置 |
| `bootstrap` | list | 否 | 启动时注入的 Bootstrap 文件列表 |
| `settings.max_iterations` | int | 否 | ReAct Loop 最大迭代次数，默认 `10` |
| `settings.max_history_turns` | int | 否 | 注入上下文的最大对话轮数，默认 `20` |

## Provider 配置

`provider.name` 必须与 `application.yml` 中 `oryxos.providers` 下的 key 对应：

```yaml
# application.yml
oryxos:
  providers:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY}
      base-url: https://api.deepseek.com
      default-model: deepseek-chat
    qwen:
      api-key: ${QWEN_API_KEY}
      base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
      default-model: qwen-max
```

```yaml
# Profile 中引用
provider:
  name: deepseek    # 必须与上面的 key 完全一致
  model: deepseek-chat
```

**API Key 规则：** 必须通过环境变量引用（`${ENV_VAR_NAME}`），不得将明文 Key 写入任何配置文件或 Profile YAML。

多个 Profile 可以引用不同的 Provider，OryxOS 在运行时通过显式映射表路由，互不干扰。

## Tool 和 Skill

### Tool

`tools` 字段列出该 Agent 可以调用的内置 Tool。未在列表中声明的 Tool 不会注册进该 Agent 的 ToolRegistry，LLM 看不到也无法调用。

```yaml
tools:
  - read_file       # 读文件
  - write_file      # 写文件
  - list_dir        # 列目录
  - shell           # 执行 shell 命令
  - http_get        # HTTP GET
  - http_post       # HTTP POST
  - save_memory     # 写入长期记忆
  - recall_memory   # 检索长期记忆
```

通过 MCP 接入的外部 Tool 在 `mcp_servers` 字段配置，详见 [Tool 体系](/zh/docs/tool)。

### Skill

`skills` 是注入 system prompt 的指令模板，**不是可执行的 Tool**。每个 Skill 对应 `.oryxos/skills/` 下的一个 Markdown 文件，由 ContextLoader 在构建 Prompt 时加载并拼入 system prompt。

```yaml
skills:
  - daily-pr-digest    # 加载 .oryxos/skills/daily-pr-digest.md
  - code-review-style  # 加载 .oryxos/skills/code-review-style.md
```

Skill 文件的内容是自然语言指令，例如：

```markdown
# Daily PR Digest

每天早上 9 点，汇总昨天的 GitHub PR 活动：
- 列出已合并的 PR（标题、作者、合并时间）
- 列出新开的 PR（标题、作者）
- 标注有冲突或超过 3 天未 review 的 PR
输出格式使用 Markdown 表格。
```

## Bootstrap 文件

Bootstrap 文件在每次对话开始时注入 system prompt，定义 Agent 的运行上下文。三个文件各有用途：

| 文件 | 谁来写 | 用途 |
| --- | --- | --- |
| `AGENTS.md` | 项目维护者 | 项目级约定：Agent 的行为边界、禁止事项、协作规则 |
| `SOUL.md` | 项目维护者 | Agent 人格定义：语气、风格、价值观 |
| `USER.md` | 用户手写 | 用户个人偏好：输出格式、习惯用语、工作背景 |

OryxOS 只读 `USER.md`，不写入。用户的运行时记忆由 Agent 通过 `save_memory` Tool 写入 `MEMORY.md`，两者严格区分。

不需要某个文件时，直接从 `bootstrap` 列表中删除即可：

```yaml
bootstrap:
  - SOUL.md    # 只保留人格定义，省略项目约定和用户偏好
```

## Settings

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `max_iterations` | `10` | ReAct Loop 最大迭代次数。每调用一次 Tool 算一次迭代，达到上限后强制返回当前结果，防止无限循环。 |
| `max_history_turns` | `20` | 注入上下文的最大对话轮数。超出部分从最旧的消息开始丢弃，控制 token 消耗。 |

```yaml
settings:
  max_iterations: 5     # 简单问答场景可以调低，减少延迟
  max_history_turns: 50 # 长文档分析场景可以调高，保留更多上下文
```
