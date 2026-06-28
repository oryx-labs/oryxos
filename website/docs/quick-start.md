# Quick Start

Get OryxOS running and chatting with your first agent in under five minutes.

## Prerequisites

- **Java 21+** — OryxOS requires Java 21 (virtual threads are core to the runtime)
- At least one LLM provider API key (DeepSeek, Qwen, Kimi, etc.)

Set your API key as an environment variable before starting:

```bash
export DEEPSEEK_API_KEY=sk-...
```

## Install

OryxOS ships as a single executable JAR. Download the latest release and put it on your PATH:

```bash
# download the JAR (replace X.Y.Z with the actual release version)
curl -L https://github.com/oryx-labs/oryxos/releases/latest/download/oryxos.jar -o oryxos.jar

# optional: make it invokable as "oryxos"
echo '#!/bin/sh\nexec java -jar /path/to/oryxos.jar "$@"' > /usr/local/bin/oryxos
chmod +x /usr/local/bin/oryxos
```

## Initialize the workspace

Run `init` in the directory where you want OryxOS to operate. It creates a `.oryxos/` workspace:

```bash
oryxos init
```

What gets created:

```text
.oryxos/
├── profiles/           # your agent Profile YAMLs go here
├── memory/
│   └── MEMORY.md       # long-term memory (written by agents)
├── skills/             # SKILL.md instruction files
├── logs/               # structured JSON logs
├── mcp_servers.yaml    # MCP server configuration
├── oryxos.db           # SQLite database (sessions, audit logs)
├── AGENTS.md           # project-level agent behavior notes
├── SOUL.md             # agent personality definition
└── USER.md             # your preferences (agents read, never write)
```

## Configure a provider

Create `application.yml` in your working directory. The minimal configuration for DeepSeek:

```yaml
oryxos:
  providers:
    deepseek:
      base-url: https://api.deepseek.com
      api-key: ${DEEPSEEK_API_KEY}
      default-model: deepseek-chat
```

The `${DEEPSEEK_API_KEY}` placeholder is resolved from the environment at startup. Never hardcode API keys in config files.

## Create your first agent

Create a Profile YAML at `.oryxos/profiles/my-agent.yaml`:

```yaml
name: my-agent
description: My first OryxOS agent
identity:
  agent_name: Assistant
  prompt: You are a helpful assistant. Be concise and accurate.
provider:
  name: deepseek
  model: deepseek-chat
tools:
  - read_file
  - shell
  - http_get
```

That's the minimum needed to get an agent running with file, shell, and HTTP capabilities.

## Start chatting

```bash
oryxos chat --profile my-agent
```

You'll get an interactive prompt. Type a message and press Enter. The agent runs a ReAct loop — it thinks, calls tools if needed, and responds. Type `exit` or press `Ctrl-D` to quit.

```text
[my-agent] > What files are in the current directory?
[thinking...]
[tool: list_dir] -> .oryxos/ application.yml oryxos.jar
There are 3 items in the current directory: the .oryxos/ workspace
folder, your application.yml config, and the oryxos.jar binary.
[my-agent] >
```

## Start the API server

To expose OryxOS as an HTTP service:

```bash
oryxos serve --port 8080
```

The server starts with 10 REST endpoints under `/api/v1`. Test it:

```bash
curl http://localhost:8080/api/v1/health
```

```json
{ "status": "ok", "uptime_ms": 1234 }
```

## What's next

- [Architecture overview](/docs/architecture) — how ReAct Loop, Providers, Memory, and Tools connect
- [ReAct Loop](/docs/react-loop) — understand the think-act-observe cycle
- [Profile YAML reference](/docs/profile) — all Profile fields explained
- [Tool system](/docs/tool) — built-in tools and how to add MCP tools
- [REST API reference](/docs/api) — all 10 endpoints with request/response examples
- [CLI reference](/docs/cli) — every command and flag
- [Memory system](/docs/memory) — how long-term memory works across sessions
