# Profile YAML Reference

A Profile is the configuration file that defines an agent. It specifies the agent's identity, which LLM provider and model to use, which tools are available, which skills are injected into the system prompt, and runtime limits. Each Profile lives as a `.yaml` file under `.oryxos/profiles/`.

---

## Full example

```yaml
name: ops-agent
description: Operations assistant for deployment and monitoring tasks

identity:
  agent_name: 运维小欧
  prompt: |
    You are a professional operations assistant.
    Be concise. Prefer shell commands over prose explanations.
    Always check disk and memory before recommending restarts.

provider:
  name: deepseek
  model: deepseek-chat
  temperature: 0.7

tools:
  - read_file
  - write_file
  - list_dir
  - shell
  - http_get
  - save_memory
  - recall_memory

skills:
  - daily-pr-digest

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
```

---

## Fields reference

### Top-level fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | string | yes | Unique identifier for this profile. Used in CLI (`--profile <name>`) and API paths (`/agents/{name}/invoke`). Must match the filename without `.yaml`. |
| `description` | string | no | Human-readable description shown in `oryxos profile list`. |
| `identity` | object | yes | Defines the agent's name and system prompt. See [Identity](#identity). |
| `provider` | object | yes | Selects the LLM provider and model. See [Provider configuration](#provider-configuration). |
| `tools` | list of strings | no | Tool names to enable for this agent. Must be registered in `ToolRegistry`. Defaults to no tools. |
| `skills` | list of strings | no | Skill file names (without `.md`) to load from `.oryxos/skills/`. Injected into the system prompt. |
| `mcp_servers` | list of strings | no | MCP server names from `mcp_servers.yaml` to connect. Tools exposed by these servers become available to the agent. |
| `channels` | list of objects | no | Channels this agent is active on. Currently only `cli` is supported in the core phase. |
| `bootstrap` | list of strings | no | Bootstrap files to prepend to the system prompt, in order. Resolved from the workspace root. |
| `settings` | object | no | Runtime tuning. See [Settings](#settings). |

### Identity

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `identity.agent_name` | string | yes | The agent's display name, shown in the CLI prompt and logged in audit records. |
| `identity.prompt` | string | yes | The agent's core system prompt. Injected first in the assembled system prompt, before bootstrap files, skills, and memory. |

### Provider (nested)

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `provider.name` | string | yes | Key that maps to a provider in `application.yml`. Must match an entry under `oryxos.providers`. |
| `provider.model` | string | yes | Model identifier passed directly to the provider API. |
| `provider.temperature` | float | no | Sampling temperature. Defaults to the provider's configured default. |

---

## Provider configuration

The `provider.name` field references a named entry in `application.yml`. OryxOS maintains an explicit `Map<String, ChatModel>` — never relies on Spring bean type scanning — so every provider must be declared:

```yaml
# application.yml
oryxos:
  providers:
    deepseek:
      base-url: https://api.deepseek.com
      api-key: ${DEEPSEEK_API_KEY}
      default-model: deepseek-chat
    qwen:
      base-url: https://dashscope.aliyuncs.com/compatible-mode
      api-key: ${DASHSCOPE_API_KEY}
      default-model: qwen-max
    kimi:
      base-url: https://api.moonshot.cn/v1
      api-key: ${KIMI_API_KEY}
      default-model: moonshot-v1-8k
```

API keys must come from environment variables. Never hardcode them in YAML files.

```bash
export DEEPSEEK_API_KEY=sk-...
export DASHSCOPE_API_KEY=sk-...
export KIMI_API_KEY=sk-...
```

The `provider.name` in a Profile must exactly match a key under `oryxos.providers`. If the key is not found, OryxOS fails at startup with a clear error.

---

## Tools and skills

### Tools

The `tools` list controls which built-in and MCP-sourced tools are available to the agent during a ReAct Loop. Only tools explicitly listed here are passed to the LLM as callable functions.

Built-in tool names:

| Name | What it does |
|------|-------------|
| `read_file` | Read a file (path whitelist enforced) |
| `write_file` | Write a file (path whitelist enforced) |
| `list_dir` | List directory contents (path whitelist enforced) |
| `shell` | Execute a shell command (command whitelist enforced) |
| `http_get` | HTTP GET request (domain whitelist enforced) |
| `http_post` | HTTP POST request (domain whitelist enforced) |
| `save_memory` | Append a note to `MEMORY.md` |
| `recall_memory` | Keyword search over `MEMORY.md` |

MCP tools from configured `mcp_servers` are available by their server-declared names (e.g. `github_create_pr`). Run `oryxos tool list` to see all registered names.

### Skills

Skills are Markdown instruction files stored in `.oryxos/skills/`. They are injected verbatim into the system prompt by `ContextLoader` — they are **not** executable tools.

```yaml
skills:
  - daily-pr-digest   # loads .oryxos/skills/daily-pr-digest.md
```

A skill file typically contains step-by-step instructions for a recurring task, context about an external system, or persona-shaping text. Because they live in the system prompt, they consume context window tokens on every turn — keep them concise.

---

## Bootstrap files

Bootstrap files are prepended to the system prompt in the order listed. They apply workspace-wide context that is shared across all agents.

| File | Who writes it | Purpose |
|------|---------------|---------|
| `AGENTS.md` | Human | Project-level agent behavior notes — conventions, naming, workflow rules |
| `SOUL.md` | Human | Agent personality definition — tone, style, values |
| `USER.md` | Human only | User preferences and background — agents read this but never write to it |

`USER.md` is strictly read-only from OryxOS's perspective. The agent-writable equivalent is `MEMORY.md`, which is updated via the `save_memory` tool.

Omit bootstrap files from the list if they are not relevant to the agent.

```yaml
bootstrap:
  - AGENTS.md
  - SOUL.md
  - USER.md
```

---

## Settings

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `settings.max_iterations` | int | `10` | Maximum number of ReAct Loop iterations per user message. Prevents runaway tool loops. When the limit is reached, the agent returns whatever partial result it has. |
| `settings.max_history_turns` | int | `20` | Number of recent conversation turns to include in the prompt. Older turns are dropped to manage context window size. One turn = one user message + one assistant response. |

If `settings` is omitted, both values use their defaults.
