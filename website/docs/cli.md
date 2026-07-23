# CLI Reference

The `oryxos` command provides sub-commands for workspace management, profile operations, interactive chat, and server control. All commands are implemented with Picocli and run on Java 21.

```bash
oryxos <command> [options]
```

**Global flags** (accepted by all commands):

| Flag | Description |
|------|-------------|
| `--help`, `-h` | Show usage for the command |
| `--version`, `-V` | Print version plus JVM/OS info |

---

## Workspace root

By default OryxOS reads and writes the `.oryxos` workspace in the current directory. The root is configurable:

| Mechanism | Applies to | Notes |
|-----------|-----------|-------|
| `ORYXOS_ROOT` (env var) | all commands | Light commands read it directly; `serve`/`gateway` pick it up through Spring relaxed binding |
| `-Doryxos.root=<path>` (JVM system property) | all commands | Highest precedence for light commands |
| `oryxos.root` in `application.yml` | `serve` / `gateway` only | Not read by the light commands (`init`/`status`/`profile`) — they never boot Spring, so use the env var or system property for them |

Resolution order for the light commands is: `-Doryxos.root` → `ORYXOS_ROOT` → default `.oryxos`. The configured root is auto-added to the file sandbox whitelist at runtime, so relocating the workspace never breaks file tools.

```bash
# Point every command at a custom workspace
ORYXOS_ROOT=/data/ws oryxos init
ORYXOS_ROOT=/data/ws oryxos status
```

---

## Getting started

### init

Initialize the workspace (default `.oryxos/`) in the current directory. Creates the directory structure and the default bootstrap files. Safe to run multiple times — existing files are never overwritten. This is a light command: it does not boot Spring, and it honors `ORYXOS_ROOT` / `-Doryxos.root`.

```bash
oryxos init
# Or into a custom workspace root:
ORYXOS_ROOT=/data/ws oryxos init
```

What gets created:

```text
.oryxos/
├── agents/       # one sub-directory per agent (AGENT.md + optional skills/ scripts/ REFERENCE.md)
├── memory/       # global long-term memory (per-agent MEMORY.md lives under agents/<name>/)
├── sessions/     # reserved (session state lives in SQLite)
├── logs/
├── AGENTS.md     # bootstrap: project-level agent behavior
├── SOUL.md       # bootstrap: agent persona
└── USER.md       # bootstrap: user preferences (read-only)
```

The `oryxos.db` SQLite database is created on first run of a heavy command (`serve`/`gateway`/`chat`), not by `init`.

### status

Print the workspace and data-file state: whether the workspace is initialized, how many agents live under `agents/`, and whether the SQLite database exists. Like `init`, this is a light command — it does not boot Spring and honors `ORYXOS_ROOT` / `-Doryxos.root`.

```bash
oryxos status
```

```text
Workspace .oryxos/  : initialized
Agent directory     : 3
SQLite database     : oryxos.db present
```

---

## Chat

### chat

Start an interactive multi-turn conversation with an agent. Runs a ReAct Loop on each message and prints the final response to the terminal. Type `/quit` or press `Ctrl-D` to end the session.

```bash
oryxos chat [--profile <name>]
```

| Flag | Default | Description |
|------|---------|-------------|
| `--profile <name>` | `default` | Profile to use for this session |

Example:

```bash
oryxos chat --profile ops-agent
```

```text
[ops-agent] > check disk usage
[tool: shell] df -h
/dev/sda1  200G  84G  116G  42%  /
Disk usage on /dev/sda1 is 42% (84G of 200G used). You have 116G free.

[ops-agent] > /quit
Session archived. Goodbye.
```

The session is persisted to SQLite and restored on subsequent `chat` invocations with the same profile and user context.

---

## Server

### serve

Start the HTTP API server. It exposes the REST API under `/api/v1` and serves the web admin console at `/admin/`. Runs synchronously with Java 21 virtual threads — no separate thread pool tuning required. As a heavy command, `serve` boots Spring and reads its workspace root from `oryxos.root` in `application.yml` (Spring relaxed binding also honors the `ORYXOS_ROOT` env var).

```bash
oryxos serve [--port <port>]
```

| Flag | Default | Description |
|------|---------|-------------|
| `--port <port>` | `8080` | TCP port to bind |

```bash
oryxos serve --port 9090
```

```text
OryxOS API server starting...
Listening on http://0.0.0.0:9090
Admin console: http://0.0.0.0:9090/admin/
```

Press `Ctrl-C` to stop.

### gateway

Start OryxOS in daemon mode (full runtime, resident). Intended for production multi-agent deployments; multi-channel mounting is an extension-phase feature. Like `serve`, it boots Spring and reads its workspace root from `oryxos.root` (env `ORYXOS_ROOT` honored via relaxed binding). Press `Ctrl-C` to stop.

```bash
oryxos gateway
```

---

## Agent (profile) management

Each agent is one directory under `<workspace>/agents/<name>/`, holding an `AGENT.md` (YAML frontmatter = the agent's profile: identity, provider, tools, settings; body = task instructions) plus optional `skills/`, `scripts/`, and `REFERENCE.md`. One directory = one agent — there is no `.oryxos/profiles/` directory. The `profile` sub-commands are light (no Spring) and read/write these `AGENT.md` files directly, honoring `ORYXOS_ROOT` / `-Doryxos.root`.

### profile list

List all agents found under `<workspace>/agents/` (one line per agent directory).

```bash
oryxos profile list
```

```text
default
ops-agent
reviewer
```

### profile create

Scaffold a new agent directory with a minimal `AGENT.md` template. Existing agents are not overwritten.

```bash
oryxos profile create <name>
```

```bash
oryxos profile create code-reviewer
# Created .oryxos/agents/code-reviewer/AGENT.md
```

### profile show

Print the full contents of an agent's `AGENT.md`.

```bash
oryxos profile show <name>
```

```bash
oryxos profile show ops-agent
```

### profile delete

Remove an agent's entire directory from the workspace.

```bash
oryxos profile delete <name>
```

---

## Inspection

### provider list

List the providers declared for this instance. The light CLI command reads them straight from the packaged `application.yml` (`oryxos.providers`), printing each provider's name and base URL. (At runtime, providers are managed as a dynamic registry backed by SQLite, seeded from these declarations.)

```bash
oryxos provider list
```

```text
deepseek     https://api.deepseek.com
qwen         https://dashscope.aliyuncs.com/compatible-mode/v1
```

### tool list

List the built-in tools. Once the `ToolRegistry` is wired at runtime this reflects the live registry (built-in plus MCP tools).

```bash
oryxos tool list
```

```text
Built-in tools:
  read_file
  write_file
  list_dir
  shell
  http_get
  http_post
  save_memory
  recall_memory
  notify
```

### session list

List sessions stored in the SQLite database (`oryxos.db` in the current directory), newest first. Columns: session id, profile name, status, last active time.

```bash
oryxos session list
```

```text
cli:user-001:ops-agent                   ops-agent        active    2025-06-01 10:05
cli:user-002:default                      default          active    2025-06-01 09:30
```
