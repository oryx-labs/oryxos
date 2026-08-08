<p align="center">
  <img src="website/public/images/logo.svg" alt="OryxOS" width="256"/>
</p>

<p align="center">
  <strong>Say it in plain language. A team of agents delivers.</strong><br/>
  <em>The self-hosted Agent Operating System for the enterprise.</em>
</p>

<p align="center">
  <a href="https://github.com/oryx-labs/oryxos/releases"><img src="https://img.shields.io/badge/version-0.1.1-orange?style=flat-square" alt="version"/></a>
  <a href="https://modelcontextprotocol.io"><img src="https://img.shields.io/badge/MCP-native-8A2BE2?style=flat-square" alt="MCP native"/></a>
  <a href="https://www.apache.org/licenses/LICENSE-2.0"><img src="https://img.shields.io/badge/license-Apache%202.0-blue?style=flat-square" alt="Apache 2.0"/></a>
</p>

---

**OryxOS is the self-hosted Agent OS for the enterprise.** Describe a task in one sentence of natural language — OryxOS breaks it down, assembles a team of agents, and they collaborate to deliver the result:

<p align="center">
  <img src="website/public/images/pipeline-en.svg" alt="one sentence → decompose → assemble an agent team → collaborate → deliver the result" width="100%"/>
</p>

**So every company can run its own agents — in plain language.** No code to define an agent, no data leaving your infrastructure, every step audited.

- **Today, shipped**: define an agent in one sentence (or one markdown directory) and it goes live instantly — with memory, 24 built-in tools, MCP connectors, skills, notifications, and cron scheduling, all on your own servers.
- **The north star**: publish a task in one sentence and an agent *team* self-organizes to deliver it — planner, specialists, reviewer — fully audited, with humans approving the dangerous steps. See the [roadmap](docs/VisionAndRoadmap.md).

> Long-term vision: enter the Apache Software Foundation as a top-level project.

## The Agent formula

Everything an agent needs is **built into the OS** — an agent just references what it wants:

<p align="center">
  <img src="website/public/images/agent-formula-en.svg" alt="natural language (md) + Memory + Tools + MCP + Skills + Knowledge + Notify = a working Agent" width="100%"/>
</p>

This is the whole point: **drive the cost of defining an agent toward zero.** You write intent; the OS supplies the capabilities, the safety rails, and the audit trail.

## What is an agent harness (and why an OS)

**An agent harness is the scaffolding around a model that turns it into a working agent:** the loop that drives reason → act → observe, the tools it can call and the execution that runs them, the context assembled before every call, the memory it accumulates, the sandbox that contains it, and the audit trail that records what it did. A bare model only generates text — the harness is what lets it *do* things, reliably and safely.

**The bottleneck for reliable agents in production is not the model — it's the harness around it.** And an enterprise never runs just one agent. OryxOS gives every agent the same production-grade harness, and runs the whole fleet like an operating system runs processes — that's the **Agent Harness OS**.

## Model → Harness → OS

| | Bare Model | Agent Harness | Agent Harness OS |
| --- | --- | --- | --- |
| Scope | A single LLM call | **One** reliable agent | A **fleet** of agents |
| Provides | Text generation | Loop, tools + execution, context, memory, sandbox, audit, delivery | Lifecycle, channels, routing, shared registries, scheduling, governance, admin + API |
| Analogy | A CPU instruction | A process with its runtime | An OS running many processes |

OryxOS is the third column — and it ships the second one for every agent it runs.

## Features

**🤖 One Directory = One Agent**
An Agent is a directory: `.oryxos/agents/<name>/AGENT.md` — YAML frontmatter plus task instructions. Its optional `skills/` directory contains relative symlinks to shared Skill entities. Every prompt receives only each bound Skill's name, description, and local path; bodies and resources load on demand. Multiple agents co-exist on one instance.

**⚡ Dynamic Agent Management**
Create an agent via REST, generate a draft `AGENT.md` from one sentence with an LLM, or just drop a directory into the workspace — a `WorkspaceWatcher` picks it up and the agent goes live with no restart.

**📦 One Binary, Zero Ceremony**
A single executable artifact with a self-implemented, fully inspectable ReAct loop and synchronous execution on virtual threads. `bin/oryx-server start` and you're live — REST API, web console, scheduler, and sandbox in one process. No extra runtimes, no sidecars.

**🔒 Private & Compliant**
Runs on your own K8s, VM, or bare metal. Data never leaves your environment. No cloud lock-in. Credentials go through environment variables and your enterprise key management.

**🔀 Dynamic Provider Registry**
LLM providers and notify channels are stored in SQLite with full CRUD — add, edit, or remove them at runtime. Explicit `name → ChatModel` routing is preserved; the model is rebuilt and cached when its key or base URL changes.

**🛡️ Security as Foundation**
Tool calls pass through file-path, command, and domain whitelists. Full audit trail from day one — every tool invocation and LLM call is persisted to the audit tables, not just logged.

**🔌 Open Standards**
Tools via MCP with a three-tier plugin model (zero-code SKILL.md → custom MCP server → native `@Tool`). Notify channels addressed by name. Agent-to-agent collaboration via A2A on the roadmap.

## Architecture

<p align="center">
  <img src="website/public/images/architecture.svg" alt="OryxOS Architecture" width="100%"/>
</p>

## Five Core Capabilities

| Capability | Description |
| --- | --- |
| **LLM Routing** | Dynamic, SQLite-backed provider registry with CRUD. Agents are provider-agnostic; explicit name → model routing keeps multi-provider dispatch correct. Switch or add providers at runtime. Local inference supported. |
| **ReAct Loop** | Self-implemented reasoning engine — no external framework. LLM decides whether and which tool to call; OryxOS executes, feeds the result back; LLM decides the next step. Synchronous execution on virtual threads; loop fully controllable. |
| **Memory** | Per-agent long-term memory (`.oryxos/agents/<name>/MEMORY.md`, keyword search, timestamped entries; global fallback when no agent context). Auto-injected into every system prompt, with a vector-retrieval upgrade path. |
| **Tool System** | Built-in file, shell, and HTTP tools. Three-tier extension: zero-code SKILL.md + community MCP server → light-code custom MCP server → heavy-code native `@Tool` method. |
| **REST API** | All capabilities exposed via REST. Any language can integrate. Business systems connect via HTTP. |

## Roadmap

**Phase 1 — Single-node Runtime Kernel** ✅ *(shipped)*
Define an agent in one sentence or one directory; it goes live with no restart. Memory, 24 built-in tools, MCP, skills, notify, cron, sandbox, full audit, REST API, and a web console — all working on a single node.

**Next — parallel tracks, pick one and start** *(see [Vision & Roadmap](docs/VisionAndRoadmap.md))*

- **A · Distributed foundation** — pluggable storage (SQLite → MySQL/PostgreSQL), stateless instances, DB-based cluster scheduling (xxl-job style)
- **B · Knowledge & memory** — built-in knowledge base + semantic memory on a shared vector subsystem
- **C · Flow orchestration** — declarative markdown flows, sub-agent delegation, A2A protocol
- **D · Self-improving agents** — auto-distill skills from successful runs
- **E · Omni-channel & multimodal** — Feishu / WeCom / DingTalk / Slack / Telegram, voice, browser, vision
- **F · Container-grade sandboxing** — Docker / SSH execution backends
- **G · Enterprise governance** — multi-tenancy, SSO/RBAC, cost dashboards, capability marketplace
- **H · Enterprise-only powers** — human-in-the-loop approval, audit-to-data-flywheel, cost governance, smart model routing
- **I · The north star** — publish a task in one sentence; an agent team self-organizes and delivers the result

## Module Structure

```text
oryxos/
├── oryxos-core          # OryxTool, Session, ReActLoop, PromptBuilder, ToolExecutor, AgentScheduler
├── oryxos-provider      # ProviderService, Function Calling adapter, explicit multi-provider map
├── oryxos-memory        # MemoryService, LongTermMemory, MemoryTools (save/recall)
├── oryxos-tool          # Built-in tools (file/shell/http), MCP Client, ToolRegistry, SandboxChecker
├── oryxos-channel-cli   # CLI channel: oryxos chat implementation
├── oryxos-web           # REST API controllers, Web admin console, GlobalExceptionHandler
├── oryxos-storage       # SQLite, SessionRepository, ToolInvocationRepository, LlmCallRepository
├── oryxos-cli           # Picocli entry, 12 subcommands, ConfigLoader
└── oryxos-boot          # Spring Boot main class, auto-configuration, dependency aggregation
```

Modules are decoupled through interfaces. Adding a new Channel or Tool requires only a new module — `oryxos-core` stays untouched.

## Quick Start

**Prerequisites**: Java 21, Maven 3.9+, and an LLM API key (DeepSeek / Qwen / OpenAI / Ollama). The Maven build installs a local Node.js on first run to bundle the admin UI — no global Node.js required.

### 1 · Build

```bash
git clone https://github.com/oryx-labs/oryxos.git
cd oryxos
mvn package -DskipTests          # compiles all modules + bundles the Vue admin UI into the fat JAR
```

### 2 · Configure the LLM key

```bash
cp config/application.yml.example config/application.yml
# edit config/application.yml → fill in the deepseek api-key
```

`config/application.yml` is gitignored, so your key stays local and is never committed. Only **one** provider key is needed to boot — Spring AI's eager OpenAI auto-config is excluded, so `serve` starts without `spring.ai.openai.api-key`.

### 3 · One-click start — server + manager

```bash
bin/start.sh                     # defaults to port 8080; or: bin/start.sh 9000
bin/stop.sh                      # stop
```

`start.sh` launches a single process that serves **both** the REST API and the Web Manager on the same port, waits for the health check to pass, then prints the URLs (`/api/v1/health`, `/admin/`, `/swagger-ui`). Logs stream to `logs/oryxos.log`. On the first run it creates `config/application.yml` from the template and asks you to fill in the key.

### CLI alternative

```bash
JAR=oryxos-boot/target/oryxos-boot-*.jar
java -jar $JAR init                       # initialize the .oryxos/ workspace (agents/ memory/ sessions/ logs/)
export DEEPSEEK_API_KEY=your-key-here      # the CLI reads the key from the environment
java -jar $JAR chat --profile default      # interactive multi-turn chat
java -jar $JAR serve --port 8080           # REST API + Web Manager (same as start.sh)
```

The workspace defaults to `.oryxos/` but is configurable — set `ORYXOS_ROOT` (or `-Doryxos.root=`, or `oryxos.root` in `application.yml`) to point OryxOS at a custom workspace directory. The configured root is auto-added to the file sandbox whitelist.

### Web Service & Web Manager

`serve` (and `bin/start.sh`) exposes one process with two faces on the same port:

| URL | What |
| --- | --- |
| `http://localhost:8080/api/v1/**` | REST API (see below) |
| `http://localhost:8080/admin/` | **Web Manager** — Vue 3 console |
| `http://localhost:8080/swagger-ui` | OpenAPI docs |

The Web Manager is a Vue 3 + Vite console (same stack and dark-orange theme as the site) with pages for **agent management** (create, one-sentence LLM generation, file editor, per-agent session and memory views), **provider and notify-channel CRUD**, **scheduled tasks**, **sessions, tools, sandbox whitelist**, and a **workspace file browser**. It is built to `oryxos-web/src/main/resources/static/admin/` and served by Spring at `/admin`, so the fat JAR ships it — no separate frontend process.

<p align="center">
  <img src="website/public/images/manager.jpg" alt="OryxOS Web Manager console" width="100%"/>
</p>

#### Manager dev mode (hot reload)

When iterating on the console UI, run the Vite dev server instead of rebuilding the JAR each time — it hot-reloads on save and bypasses the browser cache:

```bash
# 1. Keep the backend running — the dev server proxies the API to it
bin/start.sh                              # REST API on :8080

# 2. In another terminal, start the Vite dev server
cd oryxos-web/src/main/frontend
npm install                               # first time only
npm run dev                               # → http://localhost:5173/admin/
```

The dev server runs on port **5173** with base `/admin/` and proxies `/api` → `localhost:8080` (see `vite.config.js`). Edit any file under `src/` and the page updates instantly. When finished, `npm run build` bundles the production assets into `static/admin/` so the next `mvn package` ships them in the fat JAR.

## Agent Definition

**One directory = one Agent.** Each agent lives in `.oryxos/agents/<name>/` with an `AGENT.md`, optional scripts/references, and a `skills/` binding view. Shared Skill entities live under `.oryxos/skills/<name>/`; an Agent binds one through a relative symlink at `agents/<agent>/skills/<name>`. Each prompt receives only bound Skill names, descriptions, and local paths. Bodies and resources load on demand through `read_file` / `shell`; there is no `use_skill` tool or `.oryxos/profiles/` directory.

```markdown
---
name: ops-agent
description: DevOps assistant
identity:
  agent_name: ops-agent
  prompt: You are a professional DevOps assistant...
provider:
  name: deepseek          # Switch to qwen / ollama / openai — zero code change
  model: deepseek-chat
  api_key: ${DEEPSEEK_API_KEY}
tools:
  - shell
  - read_file
  - http_get
  - save_memory
  - recall_memory
schedules:
  - cron: "0 9 * * *"
settings:
  max_iterations: 10
  max_history_turns: 20
---

You are a professional DevOps assistant. When triggered, ... (task instructions)
```

Drop this directory into the workspace and the `WorkspaceWatcher` registers the agent live — no restart. Agents can also be created via `POST /api/v1/agents` or drafted from one sentence via the admin console.

## REST API

All endpoints are prefixed with `/api/v1` and every response is wrapped in a unified envelope: `{ "code": 0, "message": "success", "data": <payload>, "timestamp": ... }` (non-zero `code` on error). No auth in the core phase — assumes an internal network.

| Method | Path | Description |
| --- | --- | --- |
| `POST` `GET` | `/agents`, `/agents/{name}` | Agent CRUD (create / list / get / `PUT` update / `DELETE` archive) |
| `POST` | `/agents/{name}/invoke` | Stateless single-turn invocation |
| `GET` | `/agents/{name}/memory` | This agent's long-term memory |
| `GET` `POST` | `/agents/{name}/session`, `/agents/{name}/session/messages` | Console session + send message |
| `POST` | `/agents/{name}/generate-files` | One sentence → LLM-drafted `AGENT.md` (preview only) |
| `POST` | `/agents/{name}/files` | Save edited agent files |
| `POST` `GET` | `/providers`, `/providers/{name}` | Provider CRUD (create / list / get / `PUT` / `DELETE`) |
| `POST` `GET` | `/notify-channels`, `/notify-channels/{name}` | Notify-channel CRUD |
| `POST` `GET` | `/sessions`, `/sessions/{id}` | Session create / list / history / `DELETE` archive |
| `POST` | `/sessions/{id}/messages` | Send a message (triggers ReAct Loop) |
| `GET` `POST` `PUT` | `/schedules`, `/schedules/{id}/executions`, `/schedules/{id}/run`, `/schedules/{id}` | List / history / run-now / enable-disable |
| `GET` `POST` `DELETE` | `/sandbox/whitelist`, `/sandbox/whitelist/{category}` | List / add / remove sandbox entries (`FILE`\|`SHELL`\|`HTTP`) |
| `GET` `POST` | `/workspace/tree`, `/workspace/file` | Workspace file browser (read tree / read / write file) |
| `GET` | `/profiles` | List derived profiles (one per agent) |
| `GET` | `/tools` | List available tools |
| `GET` | `/health`, `/info` | Health check / runtime info + provider status |

## Design Principles

- **Platform before Agent** — the most important deliverable is not a powerful Agent, but the environment that lets any Agent run reliably
- **Self-implement the core** — reasoning loop is self-implemented; protocol adapters reuse mature libraries; no reinventing the wheel
- **One directory = one Agent** — `AGENT.md` + Agent-local Skill symlinks + optional scripts, not code
- **Open standards** — MCP for tools, A2A for collaboration, open formats for skills
- **Stateless instances** — state externalized from the start; the prerequisite for scaling to distributed
- **Security as foundation** — controlled tool sources, least privilege, mandatory sandbox, credentials never persisted, full audit trail from day one
- **Phased and disciplined** — build the minimal complete runtime kernel first; every architecture upgrade is proven by real usage data

## Tech Stack

| Component | Choice |
| --- | --- |
| Language / Runtime | Java 21 (virtual threads) |
| Framework | Spring Boot 3.x |
| LLM Integration | Spring AI (OpenAI-compatible protocol translation + `@Tool` schema only) |
| CLI | Picocli |
| Config | SnakeYAML |
| Persistence | SQLite + Spring Data JPA |
| Logging | Logback + SLF4J (structured JSON) |
| Build | Maven multi-module |

## Contributing

First PRs are welcome — see the [Contributing Guide](https://oryx-labs.github.io/oryxos/docs/contributing) and the [GitHub workflow primer](https://oryx-labs.github.io/oryxos/docs/github-workflow).

## License

[Apache License 2.0](LICENSE) · [oryx-labs](https://github.com/oryx-labs) · Goal: Apache Software Foundation top-level project
