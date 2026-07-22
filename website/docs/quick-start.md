# Quick Start

Get OryxOS built, configured, and serving your first agents.

## Prerequisites

- **Java 21+** — OryxOS requires Java 21 (virtual threads are core to the runtime)
- **Maven 3.9+** — to build from source
- At least one LLM provider API key (DeepSeek, Qwen, Kimi, etc.)

## Build

OryxOS is a Maven multi-module project. Build the executable boot JAR from source:

```bash
mvn clean package -DskipTests
```

The runnable JAR lands in `oryxos-boot/target/`. Everything below assumes you invoke it with `java -jar oryxos-boot/target/oryxos-boot-*.jar <command>`; the examples use `oryxos <command>` as shorthand.

## Configure a provider

Provider credentials live in an **external** config file that is kept out of version control. Copy the committed template and fill in your real keys:

```bash
cp config/application.yml.example config/application.yml
```

`config/application.yml` is gitignored (never commit real keys). The launcher loads it via `--spring.config.additional-location`; everything not overridden is inherited from the packaged defaults baked into the JAR.

Edit `config/application.yml` and set the `api-key` for the providers you use. Providers are declared as a list under `oryxos.providers`:

```yaml
oryxos:
  root: .oryxos
  providers:
    - name: deepseek
      api-key: ${DEEPSEEK_API_KEY}      # environment placeholder, resolved at startup
      base-url: https://api.deepseek.com
    - name: mock                        # built-in fake model — no key/url, for smoke testing
```

Use `${ENV_VAR}` placeholders rather than hardcoding secrets. On startup, providers declared here are **seeded into the SQLite `providers` table** (only if not already present); after that the database is authoritative and you can also manage providers dynamically via the admin console or the `/api/v1/providers` API — no restart required.

> Spring **replaces** (does not merge) list-valued keys. If you override `oryxos.providers` in your external file, list every provider you want — a partial list drops the rest.

## Initialize the workspace

Run `init` in the directory where you want OryxOS to operate. It creates the `.oryxos/` workspace:

```bash
oryxos init
```

What gets created:

```text
.oryxos/
├── agents/             # each subdirectory = one Agent (AGENT.md + optional skills/ scripts/)
├── memory/             # global long-term memory (per-agent MEMORY.md lives under agents/<name>/)
├── sessions/           # session data (reserved; sessions live in SQLite)
├── logs/               # structured JSON logs
├── AGENTS.md           # project-level agent behavior notes
├── SOUL.md             # agent personality definition
└── USER.md             # your preferences (agents read, never write)
```

The SQLite database (`oryxos.db`) is created at runtime on first boot.

### Configurable workspace root

The workspace defaults to `.oryxos`. To point OryxOS at a different location:

- `oryxos.root` in `config/application.yml` — affects the Spring-booted commands (`serve`, `gateway`)
- env `ORYXOS_ROOT` or `-Doryxos.root=` — affects the lightweight CLI commands (`init`, `status`, `profile`) and, via Spring relaxed binding, the booted commands too

The configured root is automatically added to the file sandbox whitelist at runtime, so changing it never breaks the file tools.

## The one-directory-is-one-agent model

An Agent is a directory under `.oryxos/agents/<name>/` containing an `AGENT.md`. That file is YAML frontmatter (the agent's Profile) plus a Markdown body (task instructions injected into the system prompt). There is no `.oryxos/profiles/` directory — the Profile *is* the frontmatter. See the [Profile reference](/docs/profile) for every field.

OryxOS ships with three demo agents under `.oryxos/agents/` that show the model in practice:

| Agent | What it does |
| --- | --- |
| `weather-daily` | Fetches Beijing weather from open-meteo and pushes a dressing tip via `notify` |
| `daily-tech-digest` | Pulls headlines from `hn.algolia.com`, reads an on-demand skill file, and notifies a digest |
| `github-daily` | Runs `python3 scripts/github_trending.py` via `shell` and notifies a GitHub trending report |

All three use provider `deepseek` / `deepseek-chat` and reference a notify channel by name.

## Start chatting

```bash
oryxos chat --profile weather-daily
```

You get an interactive prompt. Type a message and press Enter — the agent runs a ReAct loop (think, call tools if needed, respond). Type `exit` or press `Ctrl-D` to quit.

## Start the API server

```bash
oryxos serve --port 8080
```

This exposes the REST API under `/api/v1` and serves the **web admin console** at:

```text
http://localhost:8080/admin/
```

The admin console (Vue 3, styled to match this site) lets you manage agents, providers, notify channels, schedules, sessions, tools, and the sandbox whitelist — creating and editing agents live, with no restart. Quick health check:

```bash
curl http://localhost:8080/api/v1/health
```

```json
{ "code": 0, "message": "success", "data": { "status": "ok" }, "timestamp": 1720000000000 }
```

Every API response is wrapped in this `{ code, message, data, timestamp }` envelope (`code: 0` = success).

## What's next

- [Architecture overview](/docs/architecture) — how ReAct Loop, Providers, Memory, and Tools connect
- [ReAct Loop](/docs/react-loop) — understand the think-act-observe cycle
- [Profile reference](/docs/profile) — every AGENT.md frontmatter field explained
- [Tool system](/docs/tool) — built-in tools and how to add MCP tools
- [REST API reference](/docs/api) — endpoints with request/response examples
- [CLI reference](/docs/cli) — every command and flag
- [Memory system](/docs/memory) — how long-term memory works across sessions
