# CLI Reference

The `oryxos` command provides sub-commands for workspace management, profile operations, interactive chat, and server control. All commands are implemented with Picocli and run on Java 21.

```bash
oryxos <command> [options]
```

**Global flags** (accepted by all commands):

| Flag | Default | Description |
|------|---------|-------------|
| `--workspace <path>` | `./.oryxos` | Override the workspace directory |
| `--log-level <level>` | `info` | Log verbosity: `debug`, `info`, `warn`, `error` |

---

## Getting started

### init

Initialize the `.oryxos/` workspace in the current directory. Creates the directory structure, default bootstrap files, and an empty SQLite database. Safe to run multiple times — existing files are not overwritten.

```bash
oryxos init
```

What gets created:

```text
.oryxos/
├── profiles/
├── memory/MEMORY.md
├── skills/
├── logs/
├── mcp_servers.yaml
├── oryxos.db
├── AGENTS.md
├── SOUL.md
└── USER.md
```

### status

Print current workspace configuration and runtime state: which profiles are loaded, provider connectivity, registered tools, and active session count.

```bash
oryxos status
```

```text
Workspace:  /home/user/project/.oryxos
Profiles:   3 loaded (default, ops-agent, reviewer)
Providers:  deepseek [ok]  qwen [ok]
Tools:      7 builtin  8 mcp (github-mcp)
Sessions:   2 active
DB:         oryxos.db (1.2 MB)
```

---

## Chat

### chat

Start an interactive multi-turn conversation with an agent. Runs a ReAct Loop on each message and streams the final response to the terminal. Type `exit` or press `Ctrl-D` to end the session.

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

[ops-agent] > exit
Session archived. Goodbye.
```

The session is persisted to SQLite and restored on subsequent `chat` invocations with the same profile and user context.

---

## Server

### serve

Start the HTTP API server. Exposes 10 REST endpoints under `/api/v1`. Runs synchronously with Java 21 virtual threads — no separate thread pool tuning required.

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
Loaded profiles: default, ops-agent
Providers: deepseek [ok], qwen [ok]
Listening on http://0.0.0.0:9090
```

### gateway

Start OryxOS in daemon mode. Activates all channels configured in loaded profiles (CLI, API, and future channel types) simultaneously. Intended for production multi-agent deployments.

```bash
oryxos gateway
```

---

## Profile management

Profiles are YAML files stored in `.oryxos/profiles/`. Each file defines one agent — its identity, provider, tools, skills, and settings.

### profile list

List all profiles found in the workspace.

```bash
oryxos profile list
```

```text
NAME          PROVIDER    MODEL              TOOLS
default       qwen        qwen-max           4
ops-agent     deepseek    deepseek-chat      5
reviewer      kimi        moonshot-v1-8k     3
```

### profile create

Scaffold a new profile YAML with default fields pre-filled.

```bash
oryxos profile create <name>
```

```bash
oryxos profile create code-reviewer
# Created .oryxos/profiles/code-reviewer.yaml
```

### profile show

Print the full contents of a profile.

```bash
oryxos profile show <name>
```

```bash
oryxos profile show ops-agent
```

### profile delete

Remove a profile YAML from the workspace. Running sessions using that profile are not affected until they are next loaded.

```bash
oryxos profile delete <name>
```

---

## Inspection

### provider list

List all providers defined in `application.yml` and report their connectivity status.

```bash
oryxos provider list
```

```text
NAME       STATUS    BASE URL                        DEFAULT MODEL
deepseek   ok        https://api.deepseek.com        deepseek-chat
qwen       ok        https://dashscope.aliyuncs.com  qwen-max
kimi       error     https://api.moonshot.cn         moonshot-v1-8k
```

### tool list

List all tools available in the `ToolRegistry` — both built-in tools and tools provided by connected MCP servers.

```bash
oryxos tool list
```

```text
NAME            SOURCE           DESCRIPTION
read_file       builtin          Read a file (path whitelist enforced)
write_file      builtin          Write a file (path whitelist enforced)
list_dir        builtin          List directory contents
shell           builtin          Execute a shell command (whitelist enforced)
http_get        builtin          HTTP GET request (domain whitelist enforced)
http_post       builtin          HTTP POST request (domain whitelist enforced)
save_memory     builtin          Append to MEMORY.md
recall_memory   builtin          Search MEMORY.md by keyword
github_list_pr  mcp:github-mcp   List open pull requests
github_create_pr mcp:github-mcp  Create a pull request
```

### session list

List active sessions stored in the SQLite database.

```bash
oryxos session list
```

```text
SESSION ID      PROFILE       CHANNEL   USER         LAST ACTIVE
sess-a1b2c3     ops-agent     cli       user-001     2025-06-01 10:05
sess-d4e5f6     default       api       user-002     2025-06-01 09:30
```
