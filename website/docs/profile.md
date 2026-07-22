# Profile Reference (AGENT.md frontmatter)

In OryxOS, **one directory = one Agent**. An Agent is a directory at `.oryxos/agents/<name>/` whose `AGENT.md` file defines it. That file is:

- **YAML frontmatter** — the Agent's *Profile*: who it is, which provider/model it uses, which tools it can call, its schedules, and its runtime limits.
- **Markdown body** — the Agent's task instructions, injected into the system prompt.

There is **no `.oryxos/profiles/` directory**. The Profile *is* the frontmatter; it is not a separate file. Optional `skills/*.md`, `scripts/`, and `REFERENCE.md` can sit in the same directory and are loaded on demand from the body via `read_file` / `shell` — they are **not** declared in frontmatter.

Agents can be created and edited **dynamically** through the `/api/v1/agents` API and the web admin console — writing or editing `AGENT.md` and re-registering the agent live, with no restart.

---

## Full example

`.oryxos/agents/ops-agent/AGENT.md`:

```markdown
---
name: ops-agent
description: Operations assistant for deployment and monitoring tasks

identity:
  agent_name: 运维小欧
  prompt: |
    You are a professional operations assistant.
    Be concise. Prefer shell commands over prose explanations.

provider:
  name: deepseek
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
    message: Run the morning health check.

settings:
  max_iterations: 10
  max_history_turns: 20
---

You are a professional operations assistant. When triggered, check disk and
memory usage, summarize anything abnormal, and send the summary to the
`ops-lark` notify channel.
```

The text below the closing `---` is the **body**: it is injected into the system prompt (alongside the bootstrap files) as this agent's task instructions.

---

## Frontmatter fields

### Top-level fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | string | yes | Unique identifier for the agent. Matches the directory name and is used in CLI (`--profile <name>`) and API paths (`/api/v1/agents/{name}`). |
| `description` | string | no | Human-readable description shown in the admin console and `oryxos profile list`. |
| `identity` | object | yes | The agent's name and core prompt. See [Identity](#identity). |
| `provider` | object | yes | LLM provider and model selection. See [Provider](#provider). |
| `tools` | list of strings | no | Tool names to enable for this agent. Must be registered in `ToolRegistry`. Defaults to no tools. |
| `mcp_servers` | list of strings | no | MCP server names from `mcp_servers.yaml` to connect. Tools they expose become available to the agent. |
| `channels` | list of objects | no | Channels this agent is active on. `cli` is supported in the core phase. |
| `bootstrap` | list of strings | no | Bootstrap files to prepend to the system prompt, in order. Resolved from the workspace root. |
| `schedules` | list of objects | no | Cron-triggered runs of this agent. See [Schedules](#schedules). |
| `settings` | object | no | Runtime tuning. See [Settings](#settings). |

### Identity

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `identity.agent_name` | string | yes | The agent's display name, shown in the CLI prompt and logged in audit records. |
| `identity.prompt` | string | yes | The agent's core system prompt, injected first — before bootstrap files, the body, and memory. |

### Provider

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `provider.name` | string | yes | Key that maps to a registered provider. Resolved dynamically from the provider registry (SQLite). See [Provider configuration](#provider-configuration). |
| `provider.model` | string | yes | Model identifier passed to the provider API. |
| `provider.temperature` | float | no | Sampling temperature. Defaults to the provider's configured default. |
| `provider.api_key` | string | no | API key for the provider, as an env placeholder (`${DEEPSEEK_API_KEY}`). Never hardcode a raw key. |

---

## Provider configuration

`provider.name` references a provider by name. OryxOS keeps an explicit `name → ChatModel` mapping (Constitution III — never Spring bean-type scanning), but the mapping is **runtime-mutable**: providers are stored in the SQLite `providers` table and resolved dynamically by name.

Providers are seeded on startup from `config/application.yml` (list under `oryxos.providers`), then managed dynamically through the admin console or the `/api/v1/providers` API:

```yaml
# config/application.yml
oryxos:
  providers:
    - name: deepseek
      api-key: ${DEEPSEEK_API_KEY}
      base-url: https://api.deepseek.com
    - name: mock                     # built-in fake model — no key/url
```

API keys must come from environment variables. Never hardcode them.

```bash
export DEEPSEEK_API_KEY=sk-...
```

If a Profile's `provider.name` has no matching provider in the registry, the agent fails with a clear error.

---

## Tools

The `tools` list controls which built-in and MCP-sourced tools are available to the agent during a ReAct Loop. Only tools listed here are passed to the LLM as callable functions.

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
| `notify` | Push a message to a notify channel referenced by name |

MCP tools from configured `mcp_servers` are available by their server-declared names (e.g. `github_create_pr`). Run `oryxos tool list` to see all registered names. See the [Tool system](/docs/tool) for details, including how the `notify` tool resolves a channel by name.

### Sub-instructions and scripts (no frontmatter field)

Reusable instructions and helper scripts live **inside the agent directory**, not in frontmatter:

```text
.oryxos/agents/ops-agent/
├── AGENT.md            # frontmatter (Profile) + body (task instructions)
├── skills/
│   └── deploy-runbook.md
├── scripts/
│   └── collect_metrics.py
└── REFERENCE.md
```

The body tells the agent when to pull these in — `read_file` for `skills/*.md` and `REFERENCE.md`, `shell` for `scripts/`. This is progressive disclosure *within one agent*: there is no global skill index and no separate `skills` frontmatter key.

---

## Bootstrap files

Bootstrap files are prepended to the system prompt in the order listed. They apply workspace-wide context shared across all agents.

| File | Who writes it | Purpose |
|------|---------------|---------|
| `AGENTS.md` | Human | Project-level agent behavior notes — conventions, naming, workflow rules |
| `SOUL.md` | Human | Agent personality definition — tone, style, values |
| `USER.md` | Human only | User preferences and background — agents read this but never write to it |

`USER.md` is strictly read-only from OryxOS's perspective. The agent-writable equivalent is `MEMORY.md`, updated via the `save_memory` tool. Omit any file not relevant to the agent.

---

## Schedules

`schedules` declares cron-triggered runs of the agent. Each entry fires the agent's body as the task at the given time. Scheduled tasks can also be listed, run on demand, and enabled/disabled through the `/api/v1/schedules` API and the admin console.

```yaml
schedules:
  - id: morning-check
    cron: "0 0 8 * * *"     # Spring cron: sec min hour day month weekday
    zone: Asia/Shanghai
    message: Run the morning health check.
```

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Unique id for this schedule within the agent. |
| `cron` | string | Spring 6-field cron expression. |
| `zone` | string | IANA time zone (e.g. `Asia/Shanghai`). |
| `message` | string | The trigger message handed to the agent when the schedule fires. |

---

## Settings

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `settings.max_iterations` | int | `10` | Maximum ReAct Loop iterations per user message. Prevents runaway tool loops. When the limit is reached, the agent returns whatever partial result it has. |
| `settings.max_history_turns` | int | `20` | Number of recent conversation turns to include in the prompt. Older turns are dropped to manage context window size. One turn = one user message + one assistant response. |

If `settings` is omitted, both values use their defaults.
