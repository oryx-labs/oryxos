<p align="center">
  <img src="website/public/images/logo.svg" alt="OryxOS" width="256"/>
</p>

<p align="center">
  <strong>Build enterprise AI applications. Govern every AI action.</strong><br/>
  <em>The open-source Agent Harness OS for the enterprise.</em>
</p>

<p align="center">
  <a href="https://github.com/oryx-labs/oryxos/releases"><img src="https://img.shields.io/badge/version-0.1.8-orange?style=flat-square" alt="version"/></a>
  <a href="https://modelcontextprotocol.io"><img src="https://img.shields.io/badge/MCP-native-8A2BE2?style=flat-square" alt="MCP native"/></a>
  <a href="https://www.apache.org/licenses/LICENSE-2.0"><img src="https://img.shields.io/badge/license-Apache%202.0-blue?style=flat-square" alt="Apache 2.0"/></a>
</p>

<p align="center">
  <a href="README.md">English</a> · <a href="README.zh-CN.md">中文</a>
</p>

---

**OryxOS is an open-source Agent Harness OS for enterprise AI applications.** Build R&D automation, knowledge support, ticket collaboration, operations diagnostics, and other domain applications on a shared runtime. Applications bring their business rules, data, systems, and user experience; OryxOS provides the execution, governance, and optimization foundation.

<p align="center">
  <img src="website/public/images/runtime-governance-en.svg" alt="enterprise AI applications connect to the OryxOS Agent Runtime and governance foundation" width="100%"/>
</p>

OryxOS lets application teams focus on domain outcomes while the platform organizes how AI Tasks run, how Agents access capabilities, and how every action is governed, observed, and improved. The application composes Agents, models, Knowledge, Tools, MCP servers, and Flows; OryxOS supplies the shared runtime semantics that make those capabilities safe and operable across the organization.

- **Available today**: define an Agent in one sentence or one markdown directory and run it on your own infrastructure with model providers, Memory, Knowledge, built-in Tools, MCP, Skills, notifications, scheduling, sandbox controls, audit records, REST APIs, and a Web Manager.
- **Direction**: make durable, governed Tasks the common unit for enterprise AI applications, with model routing and cost optimization, stronger recovery, approval, evaluation, and cross-application optimization over time. See the [roadmap](VisionAndRoadmap.md).

> Long-term vision: enter the Apache Software Foundation as a top-level project.

## The Agent formula

Everything an agent needs is **built into the OS** — an agent just references what it wants:

<p align="center">
  <img src="website/public/images/agent-formula-en.svg" alt="natural language (md) + Memory + Tools + MCP + Skills + Knowledge + Notify = a working Agent" width="100%"/>
</p>

This is the practical starting point: **drive the cost of defining an Agent toward zero.** You write intent; the runtime supplies capabilities, safety boundaries, and execution evidence.

## What is an agent harness (and why an OS)

**An agent harness is the scaffolding around a model that turns it into a working agent:** the loop that drives reason → act → observe, the tools it can call and the execution that runs them, the context assembled before every call, the memory it accumulates, the sandbox that contains it, and the audit trail that records what it did. A bare model only generates text — the harness is what lets it *do* things, reliably and safely.

**The bottleneck for reliable enterprise AI is the runtime around the model and the governance of its actions.** OryxOS gives every Agent a consistent execution environment and gives every application shared Task, capability, policy, audit, cost, routing, and observability semantics. That is the **Agent Harness OS**.

### A governed execution path

An OryxOS Task connects business context to model and Tool execution. Governance is part of the runtime path, not a separate report added after the work:

```text
Task -> Identity -> Authorization -> Policy -> Approval
     -> Model Routing / Tool Execution -> Audit / Trace / Cost
     -> Artifact / State Update
```

The current agent-team pipeline remains useful as an example of how a Task can be decomposed and delivered. Team orchestration and broader multi-agent coordination are evolving capabilities, not the definition of the current product:

<p align="center">
  <img src="website/public/images/pipeline-en.svg" alt="a task can be decomposed into collaborating agents and delivered with audit and human approval" width="100%"/>
</p>

## Model → Harness → OS

| | Bare Model | Agent Harness | Agent Harness OS |
| --- | --- | --- | --- |
| Scope | A single LLM call | **One** reliable Agent | Enterprise applications and their **Tasks** |
| Provides | Text generation | Loop, tools + execution, context, memory, sandbox, audit, delivery | Task lifecycle, channels, routing, shared registries, scheduling, governance, administration, and APIs |
| Analogy | A CPU instruction | A process with its runtime | An OS running many processes |

OryxOS is the third column: it provides the shared runtime and governance around every Agent and application it runs.

## Features

The feature list below describes the current single-node runtime unless a capability is marked **evolving** or **exploratory**. The project positioning is broader than the current implementation: OryxOS is building toward a shared Agent Runtime, Task Runtime, and Control Plane for enterprise AI applications.

**🤖 One Directory = One Agent**
An Agent is a directory: `.oryxos/agents/<name>/AGENT.md` — YAML frontmatter plus task instructions. Its optional `skills/` directory contains relative symlinks to shared Skill entities. Every prompt receives only each bound Skill's name, description, and local path; bodies and resources load on demand. Multiple agents co-exist on one instance.

**⚡ Dynamic Agent Management**
Create an agent via REST, generate a draft `AGENT.md` from one sentence with an LLM, or update its workspace directory during a maintenance window. Single-node mode uses `WorkspaceWatcher`; cluster mode uses version polling and periodic reconciliation to reload definitions without restarting.

**📦 One Binary, Zero Ceremony**
A single executable artifact with a self-implemented, fully inspectable ReAct loop and synchronous execution on virtual threads. `bin/oryx-server start` and you're live — REST API, web console, scheduler, and sandbox in one process. No extra runtimes, no sidecars.

**🔒 Private & Compliant**
Runs on your own K8s, VM, or bare metal. Data never leaves your environment. No cloud lock-in. Credentials go through environment variables and your enterprise key management.

**🧭 Model Routing and Cost Optimization**
LLM providers are registered dynamically and selected through an explicit `name → ChatModel` route. OryxOS records model usage as part of every Task and provides the control point for Provider switching, fallback, budget enforcement, and policy-aware routing. The current SQLite-backed registry supports runtime CRUD and local inference; organization-wide routing, cost optimization, quotas, and data-boundary policies are **evolving**.

**🛡️ Governed AI Actions**
Tool calls pass through file-path, command, and domain whitelists. Full audit trail from day one — every Tool invocation and LLM call is persisted to audit tables, not just logged. OryxOS is evolving this into an identity-aware governance path where authorization, policy, approval, audit, cost, and execution boundaries follow every action.

**🔌 Enterprise Tools and Open Standards**
Tools are available through a three-tier extension model: zero-code `SKILL.md` → custom MCP server → native `@Tool`. Each enterprise capability can be reused by many applications while entering the same execution, sandbox, audit, and observability path. Notify channels are addressed by name; broader ecosystem interoperability and Agent-to-Agent collaboration are **exploratory**.

## Architecture

<p align="center">
  <img src="website/public/images/architecture.svg" alt="OryxOS Architecture" width="100%"/>
</p>

The current implementation diagram shows the single-node runtime. The target platform model separates enterprise applications from shared OryxOS services: the **Agent Runtime** executes Context, Session, model turns, and Tools; the **Task Runtime** manages durable work and Artifacts; **model routing and cost optimization** select and account for model use; and the **Control Plane** coordinates identity, policy, approval, audit, cost, and evaluation. These layers are evolving at different speeds, so the roadmap below distinguishes what is available from what is still being built.

## Core Platform Capabilities

| Capability | Status | Description |
| --- | --- | --- |
| **Model Routing and Cost Optimization** | Available / evolving | Dynamic, SQLite-backed Provider registry with CRUD. Agents are Provider-agnostic; explicit name → model routing keeps multi-provider dispatch correct. OryxOS records model calls and provides the common control point for runtime switching and fallback. Organization-wide routing policies, quotas, budget enforcement, and cost optimization are evolving. |
| **ReAct Loop** | Available | Self-implemented reasoning engine — no external framework. The model decides whether and which Tool to call; OryxOS executes, feeds the result back, and continues the turn. Synchronous execution on virtual threads; loop fully controllable. |
| **Memory** | Available | Per-Agent long-term memory (`.oryxos/agents/<name>/MEMORY.md`, timestamped entries; global fallback when no Agent context). Auto-injected into every system prompt. Semantic retrieval uses vector, keyword, and recency routes when `embedding.*` is configured. |
| **Knowledge** | Available | Named knowledge bases (`.oryxos/knowledge/<name>/`) with chunking, embedding indexing, hybrid retrieval, citations, hot reload, and Admin Console lifecycle. The backend contract remains extensible for enterprise storage. |
| **Tool and MCP Runtime** | Available | Built-in file, shell, and HTTP Tools. Three-tier extension: zero-code `SKILL.md` and MCP server → custom MCP server → native `@Tool` method. Enterprise extensions reuse the same sandbox, audit, and observability path. |
| **Governance Runtime** | Available / evolving | File-path, command, and domain boundaries plus persisted audit records are available today. Identity propagation, policy distribution, human approval, organization boundaries, and complete Control Plane semantics are evolving. |
| **Durable Tasks and Flows** | Available / evolving | Sessions and execution persistence are available. Checkpoint, waiting, cancellation, retries, recovery, artifacts, and governed Flow semantics are being strengthened as the shared Task Runtime. |
| **Cost, Trace, and Evaluation** | Available / evolving | Trace IDs and call records connect agent turns, model calls, and Tool invocations. Cost attribution, budgets, evaluation, and regression feedback are evolving into cross-application operational capabilities. |
| **Runtime API** | Available | Runtime capabilities are exposed through REST so applications and business systems can integrate from any language. Durable Task APIs, governance APIs, and SDKs are evolving. |

## Roadmap

**Available — Single-node Runtime Kernel** ✅
Define an Agent in one sentence or one directory; it goes live with no restart. Memory, built-in Tools, MCP, Skills, notifications, scheduling, sandbox controls, audit records, REST API, and the Web Manager work on a single node.

**Evolving — shared Task Runtime, model routing and cost optimization, and Control Plane** *(see [Vision & Roadmap](VisionAndRoadmap.md))*

- **A · Durable Tasks** — stable Task lifecycle, checkpoints, artifacts, waiting, cancellation, retry, and recovery semantics
- **B · Model routing and cost optimization** — routing across quality, latency, availability, cost, and data-boundary requirements; Provider fallback, quotas, budgets, and usage attribution
- **C · Governance Control Plane** — identity context, policy distribution, approval workflows, organization boundaries, and administrative lifecycle
- **D · Cost and quality operations** — cross-application cost attribution, budgets, trace analysis, evaluation, and regression feedback
- **E · Distributed foundation** — shared storage, stateless instances, scheduling, high availability, and multi-node operations
- **F · Flow orchestration** — declarative steps, dependencies, timeouts, compensation, and governed external side effects

**Exploratory — ecosystem and application direction**

- **G · Cross-application optimization** — shared routing, capability catalogs, evaluation sets, and operational learning across applications
- **H · Governed multi-Agent systems** — planner, specialists, reviewers, and A2A collaboration on stable Task and Policy semantics
- **I · Ecosystem interoperability** — portable extensions and open integrations across runtimes, identity systems, and execution hosts
- **J · Enterprise AI application examples** — reusable patterns for R&amp;D automation, knowledge support, ticket collaboration, and operations diagnostics

## Module Structure

```text
oryxos/
├── oryxos-core          # OryxTool, Session, ReActLoop, PromptBuilder, ToolExecutor, AgentScheduler
├── oryxos-persona       # Persona preset catalog (12 built-in) + user-defined persona CRUD
├── oryxos-provider      # ProviderService, Function Calling adapter, explicit multi-provider map
├── oryxos-memory        # MemoryService, LongTermMemory, MemoryTools (save/recall)
├── oryxos-knowledge     # Knowledge base: local backend plugin, chunk/embed/index pipeline,
│                        #   hybrid retrieval (vector + keyword + RRF), retrieve_knowledge
├── oryxos-tool          # Built-in tools (file/shell/http), MCP Client, ToolRegistry, SandboxChecker
├── channels/            # Channel adapters, grouped under the oryxos-channels aggregator
│   ├── oryxos-channel-cli   # CLI channel: oryxos chat implementation
│   ├── oryxos-channel-feishu  # Feishu inbound channel (long connection)
│   ├── oryxos-channel-wecom  # WeCom smart-bot inbound channel
│   ├── oryxos-channel-dingtalk  # DingTalk stream inbound channel
│   ├── oryxos-channel-slack  # Slack inbound channel (Socket Mode)
│   ├── oryxos-channel-discord  # Discord inbound channel (Gateway)
│   ├── oryxos-channel-telegram  # Telegram bot inbound channel
│   ├── oryxos-channel-whatsapp  # WhatsApp inbound channel
│   ├── oryxos-channel-teams  # Microsoft Teams inbound channel
│   ├── oryxos-channel-gchat  # Google Chat inbound channel
│   ├── oryxos-channel-mattermost  # Mattermost inbound channel
│   ├── oryxos-channel-matrix  # Matrix inbound channel
│   ├── oryxos-channel-qq  # QQ official bot inbound channel
│   ├── oryxos-channel-douyin  # Douyin OpenAPI inbound channel
│   ├── oryxos-channel-weixin  # WeChat iLink bot inbound channel
│   ├── oryxos-channel-weixin-kf  # WeChat Customer Service inbound channel
│   ├── oryxos-channel-weixin-mp  # WeChat Official Account inbound channel
│   ├── oryxos-channel-weixin-mini  # WeChat Mini Program inbound channel
│   └── oryxos-channel-alipay  # Alipay lifestyle inbound channel
├── oryxos-web           # REST API controllers, Web admin console, GlobalExceptionHandler
├── oryxos-storage       # SQLite, SessionRepository, ToolInvocationRepository, LlmCallRepository
├── oryxos-cli           # Picocli entry, 12 subcommands, ConfigLoader
└── oryxos-boot          # Spring Boot main class, auto-configuration, dependency aggregation
```

Modules are decoupled through interfaces. Adding a new Channel or Tool requires only a new module — `oryxos-core` stays untouched.

## Quick Start

**Prerequisites**: Java 21 and Maven 3.9+. The default local setup uses the built-in `mock` provider and needs no LLM API key. The Maven build installs a local Node.js on first run to bundle the admin UI — no global Node.js required.

**Quickest setup — [SDKMAN!](https://sdkman.io/)** (recommended): the repo ships a `.sdkmanrc` that pins the exact JDK and Maven versions. After installing SDKMAN, enable `sdkman_auto_env=true` in `~/.sdkman/etc/config`, then:

```bash
cd oryxos
sdk env install    # installs the JDK + Maven versions declared in .sdkmanrc
```

From then on, every `cd` into the project directory auto-switches to the right toolchain. If you prefer to install Java and Maven yourself (e.g. via `apt`, `brew`, or a tarball), make sure `java -version` reports 21+ and `mvn -version` reports 3.9+.

**Windows**: Built-in workspace storage requires a native POSIX filesystem, so start the server inside WSL2 (Ubuntu). Clone into the Linux home directory, for example `~/oryxos`, then build and run there. A checkout on a Windows drive such as `/mnt/d/...` is still NTFS and fails the same startup check. Docker on WSL2 is the other supported path.

### 1 · Build

```bash
git clone https://github.com/oryx-labs/oryxos.git
cd oryxos
mvn package -DskipTests          # compiles all modules + bundles the Vue admin UI into the fat JAR
```

### 2 · Optional: configure an external provider

```bash
cp config/application.yml.example config/application.yml
export DEEPSEEK_API_KEY=your-key-here
# Uncomment the deepseek entry in config/application.yml and set author.provider: deepseek
```

The example starts with `mock`; no provider key is required to boot. For an external provider, keep the key in an environment variable (or deployment Secret) and reference it with `${DEEPSEEK_API_KEY:}` in the provider seed. Do not paste the key into YAML. The example config is gitignored, but that is not a reason to store credentials there.

### 3 · One-click start — server + manager

```bash
bin/start.sh                     # defaults to port 8080; or: bin/start.sh 9000
bin/stop.sh                      # stop
```

`start.sh` launches a single process that serves **both** the REST API and the Web Manager on the same port, waits for the health check to pass, then prints the URLs (`/api/v1/health`, `/admin/`, `/swagger-ui`). Logs stream to `logs/oryxos.log`. On first run it creates `config/application.yml` from the keyless template and starts with `mock`.

Provider YAML entries seed only providers missing from the database. Once seeded, the encrypted database registry is authoritative; changing an environment variable or YAML value does not rotate an existing provider. Add or rotate runtime credentials through the admin console or `PUT /api/v1/providers/{name}`. Back up and keep `ORYXOS_MASTER_KEY` stable in production so stored credentials remain decryptable; losing or changing it can make existing credentials unavailable.

### CLI alternative

```bash
JAR=oryxos-boot/target/oryxos-boot-*.jar
java -jar $JAR init                       # initialize the .oryxos/ workspace (agents/ memory/ sessions/ logs/)
# For an external provider, add its environment-placeholder seed to config/application.yml first.
export DEEPSEEK_API_KEY=your-key-here
java -jar $JAR chat --profile default      # interactive multi-turn chat
java -jar $JAR serve --port 8080           # REST API + Web Manager (same as start.sh)
```

The workspace defaults to `.oryxos/` but is configurable — set `ORYXOS_ROOT` (or `-Doryxos.root=`, or `oryxos.root` in `application.yml`) to point OryxOS at a custom workspace directory. The selected workspace root and its checked native execution view are auto-added to the file sandbox whitelist. Shared deployments use the `shared-posix` provider with a pre-provisioned `.workspace-id`; see the [shared workspace guide](docs/SharedVolumeGuide.md) for plugin requirements, conflict handling, output isolation and recovery.

### Docker alternative

Every release also ships a multi-arch container image (`linux/amd64` + `linux/arm64`) on GHCR — no Java 21 install, no tarball download:

```bash
docker run -d --name oryxos -p 8080:8080 -v oryxos-data:/data ghcr.io/oryx-labs/oryxos:latest
curl http://localhost:8080/api/v1/health      # → {"code":0,…}
```

The container boots keyless (configure providers in the web console afterwards) and keeps **all state** — `config/`, the `.oryxos/` workspace, `oryxos.db`, logs — in the `/data` volume, so upgrading means pulling a new tag and recreating the container. The image runs as a non-root user and carries a built-in healthcheck against `/api/v1/health`; see `docker-compose.yml` at the repo root for a ready-to-use compose stack. To build the image locally from source: `make docker` (after `make build`).

> The example above uses default SQLite — run **one** container. Multi-replica deployments require shared PostgreSQL and a shared workspace. See the [K8s deployment guide](docs/K8sDeployGuide.md) and [shared-volume requirements](docs/SharedVolumeGuide.md). Local kind validation does not establish cross-host storage HA; the [042 acceptance record](specs/042-workspace-storage/acceptance.md) keeps that verification pending.

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
  - key: daily-brief
    name: Daily brief
    cron: "0 0 9 * * *"
    zone: Asia/Shanghai
settings:
  max_iterations: 10
  max_history_turns: 20
---

You are a professional DevOps assistant. When triggered, ... (task instructions)
```

Schedules with an omitted or blank `zone` use UTC, consistently across replicas. To keep a local-time schedule when upgrading from the previous system-time-zone default, set `zone` explicitly to the deployment's former time zone. For example, a daily `09:00` schedule without `zone` now runs at `17:00` in Shanghai; set `zone: Asia/Shanghai` to keep `09:00` Shanghai time.

In single-node mode, `WorkspaceWatcher` registers workspace changes without restarting. In cluster mode, use the management API; direct file changes require a stopped-writer maintenance window and are picked up by periodic reconciliation. Agents can also be created via `POST /api/v1/agents` or drafted from one sentence via the admin console.

## REST API

All endpoints are prefixed with `/api/v1` and every response is wrapped in a unified envelope: `{ "code": 0, "message": "success", "data": <payload>, "timestamp": ... }` (non-zero `code` on error). Authentication is configurable and disabled by default; configure web authentication, API keys and authorization for your deployment before exposing protected operations.

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

- **Platform before Agent** — the most important deliverable is not a powerful Agent, but the shared Agent Runtime and Task Runtime that let enterprise applications run reliably
- **Governance is runtime semantics** — identity, authorization, policy, approval, audit, trace, and cost follow model and Tool actions through the execution path
- **Stable kernel, open extensions** — applications can bring their own models, Tools, MCP servers, knowledge, identity systems, and workflows without bypassing the common runtime boundary
- **Positioning is not implementation status** — the Agent Harness OS is the target platform model; each capability is documented as available, evolving, or exploratory
- **Self-implement the core** — reasoning loop is self-implemented; protocol adapters reuse mature libraries; no reinventing the wheel
- **One directory = one Agent** — `AGENT.md` + Agent-local Skill symlinks + optional scripts, not code
- **Open standards** — MCP for tools, A2A for collaboration, open formats for skills
- **Stateless instances** — state externalized from the start; the prerequisite for scaling to distributed
- **Security as foundation** — controlled tool sources, least privilege, mandatory sandbox, encrypted storage for managed secrets, audit trails
- **Phased and disciplined** — build the minimal complete runtime kernel first; every architecture upgrade is proven by real usage data

## Tech Stack

| Component | Choice |
| --- | --- |
| Language / Runtime | Java 21 (virtual threads) |
| Framework | Spring Boot 3.x |
| LLM Integration | Spring AI (OpenAI-compatible protocol translation + `@Tool` schema only) |
| CLI | Picocli |
| Config | SnakeYAML |
| Persistence | SQLite (default) / PostgreSQL (cluster), Spring Data JPA; pluggable local/shared POSIX workspace |
| Logging | Logback + SLF4J (structured JSON) |
| Build | Maven multi-module |

## Contributing

First PRs are welcome — see the [Contributing Guide](https://oryx-labs.github.io/oryxos/docs/contributing) and the [GitHub workflow primer](https://oryx-labs.github.io/oryxos/docs/github-workflow).

## Contact group

<img width="320" height="380" alt="image" src="https://github.com/user-attachments/assets/4a941b64-52c0-4960-b2c2-438e2bf6b267" />


## License

[Apache License 2.0](LICENSE) · [oryx-labs](https://github.com/oryx-labs) · Goal: Apache Software Foundation top-level project
