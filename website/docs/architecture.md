# Architecture

OryxOS is a Spring Boot 3.x single-binary application running on JDK 21. The entire system — LLM routing, reasoning loop, memory, tools, REST API, and the web admin console — ships as one fat JAR. No external dependencies are required beyond a JVM and the LLM provider credentials you configure. State is stored in SQLite and on the local filesystem under the workspace directory (`.oryxos/` by default, configurable — see below).

The reasoning engine is a self-implemented ReAct loop. Spring AI Alibaba handles LLM protocol translation; OryxOS owns the loop, the context assembly, the tool dispatch, and the audit records.

## Architecture Diagram

![OryxOS Architecture](/images/architecture.svg)

## Layer Diagram

![OryxOS Layer Diagram](/images/layer-diagram.svg)

**Channel Layer** — the entry points. CLI handles interactive use and local debugging. The REST API handles business system integration and backs the web admin console. Both funnel into the same engine.

**Engine Layer** — the Agent brain. `ReActLoop` drives each iteration: assemble prompt, call LLM, inspect response, execute tools, append results, repeat. `PromptBuilder` assembles the four-part prompt on each iteration. `ToolExecutor` dispatches tool calls, enforces sandbox policy, and writes audit records.

**Capability Layer** — what the engine calls out to. `ProviderService` routes LLM calls to the configured provider, resolved dynamically by name from the provider registry. `MemoryService` provides conversation history and long-term memory. `ToolRegistry` holds all registered tools, built-in and MCP-backed alike.

**Storage Layer** — persistence. SQLite stores sessions, tool-invocation and LLM-call audit records, scheduled tasks and their execution history, per-agent memory entries, notify channels, and provider definitions. The filesystem stores agent directories (`AGENT.md` plus optional `skills/`, `scripts/`, `REFERENCE.md`), Bootstrap files, and `MEMORY.md` — anything a user might want to edit directly or track in git.

## One Directory = One Agent

Every Agent is a single directory: `.oryxos/agents/<name>/`. There is no separate `.oryxos/profiles/` — the Profile *is* the agent.

- `AGENT.md` = YAML frontmatter (the Agent's Profile: `name`, `description`, `identity`, `provider`/`model`, `tools`, `mcp_servers`, `channels`, `bootstrap`, `settings`, `schedules`) + a body of task instructions injected into the system prompt. `AgentLoader.deriveProfile(agentDir)` turns the frontmatter into the `Profile` the runtime understands.
- Optional `skills/*.md` sub-instructions, `scripts/`, and `REFERENCE.md` sit in the same directory and are loaded **on demand** through the ordinary `read_file` / `shell` tools — no global skill index, no `use_skill`.
- Long-term memory is **per-agent**: `.oryxos/agents/<name>/MEMORY.md` (falling back to the global `.oryxos/memory/MEMORY.md` when there is no agent context). Memory lines are timestamped, and every trigger of an agent is auto-recorded to that agent's archival memory.

## Dynamic Agent Lifecycle

Agents can be created, updated, and removed at runtime — no restart required.

- **CRUD via REST** — `POST /api/v1/agents` writes `.oryxos/agents/<name>/AGENT.md`, derives the Profile, and registers the agent (rolling back on failure); `PUT` updates it (a schedule change unregisters then re-registers); `DELETE` performs a soft delete: unschedule → remove from registry → archive the directory (never a physical delete).
- **Drop-in via WorkspaceWatcher** — a JDK `WatchService`-based watcher observes `.oryxos/agents/`. Dropping a directory in makes the agent live immediately; the same watcher keeps the registry in sync with on-disk edits.
- **One-sentence generation** — `POST /api/v1/agents/{name}/generate-files` turns a single sentence into a draft `AGENT.md` via the LLM. The draft is **preview only** — it is neither saved nor registered until the user confirms and saves it (`POST /api/v1/agents/{name}/files`).

## Dynamic Provider & Notify-Channel Registries

Providers and notify channels are no longer static config — both are stored in SQLite and managed through CRUD endpoints.

- **Providers** (`providers` table) — `ProviderApiController` exposes create/list/get/update/delete. At runtime the engine resolves the `ChatModel` dynamically by provider name; models are built and cached by `(name | apiKey | baseUrl)`, so editing a key or base URL rebuilds the model on the next call. The `oryxos.providers[]` config is *seeded* into the table on first startup, after which the database is authoritative. Constitution principle III (an explicit `provider name → ChatModel` map) is preserved — the map is simply runtime-mutable. The `mock` provider is a built-in fake model needing no key or URL.
- **Notify channels** (`notify_channels` table) — `NotifyChannelApiController` exposes the same CRUD. A channel has a `type` (`feishu` | `wecom` | `dingtalk` | `webhook`) and a URL. The `notify` tool references a channel **by name** (in the `AGENT.md` body, e.g. "notify team-lark"); the tool resolves the registered channel to its adapter and URL. There is no `notify_channels` field in the frontmatter.

## Configurable Workspace Root

The workspace directory defaults to `.oryxos` but is configurable:

- `oryxos.root` in `application.yml` — affects the Spring-booted commands (`serve` / `gateway`).
- `ORYXOS_ROOT` env var or `-Doryxos.root=` — affects the zero-Spring light CLI commands (`init` / `status` / `profile`) and, through Spring relaxed binding, the booted commands too.

The configured root is auto-added to the file sandbox whitelist at runtime, so changing the root never breaks the file tools.

## REST API Surface

All endpoints live under `/api/v1`, speak JSON, and (in the core phase, on the assumption of an internal network) require no authentication. **Every** response is wrapped in a unified envelope:

```json
{ "code": 0, "message": "success", "data": {}, "timestamp": 1720000000000 }
```

`code` 0 means success; errors carry a non-zero code and message (400 for bad input, 404 for a missing resource). Ten controllers make up the surface:

| Controller | Responsibility |
| --- | --- |
| `SystemApiController` | `GET /health`, `GET /info` (runtime info + configured providers) |
| `AgentApiController` | Agent CRUD, `invoke`, per-agent `memory` / `session`, `generate-files`, `files` |
| `ProviderApiController` | Provider CRUD (SQLite-backed) |
| `NotifyChannelApiController` | Notify-channel CRUD (SQLite-backed) |
| `SessionApiController` | Session create / list / get / archive; `messages` runs the ReAct loop synchronously |
| `ScheduleApiController` | List schedules, execution history, run-now, enable/disable |
| `ProfileApiController` | List derived profiles (one per agent directory) |
| `ToolApiController` | List registered tools (built-in + MCP) |
| `SandboxWhitelistController` | Runtime-manage the `FILE` / `SHELL` / `HTTP` whitelists |
| `WorkspaceApiController` | Read-only workspace tree; read/write a file with path-traversal guard |

## Web Admin Console

A Vue 3 + Vite single-page app is served at `/admin/`, styled to match the marketing website. The sidebar groups: **概览 (Overview)** / **Agent 列表** / **定时任务 (Schedules)** / **Skill 列表** (placeholder) / **知识库 (Knowledge Base)** (placeholder) / and an **OS Runtime** group holding **会话列表 (Sessions)**, **Provider 列表**, **Tool 列表**, **Notify 渠道**, and **SandBox 列表**.

- The Agent list shows a description column and per-row **立即触发 (trigger now, via a console session)**, **详情 (detail)**, and **删除 (delete)** actions; the detail view has tabs for basic info / generate / files / session / memory, including the one-sentence "generate" flow (preview then save). The memory tab shows two tables — 核心记忆 (core) and 归档记忆 (archival).
- The Provider and Notify-channel pages offer full CRUD; provider api-keys are shown in plaintext by design.
- Add/edit forms are modal dialogs, and every list has a refresh button. The workspace file browser exposes the on-disk agent tree.

## Module Structure

| Module | Responsibility |
| --- | --- |
| `oryxos-core` | Core abstractions and interfaces: `OryxTool`, `Session`, `Profile`, `ContextLoader`, `ReActLoop`, `PromptBuilder`, `ToolExecutor`, `AgentService`, `AgentLoader` |
| `oryxos-provider` | LLM routing: `ProviderService`, Function Calling format adaptation, dynamic `provider name → ChatModel` resolution backed by the provider registry |
| `oryxos-memory` | Memory: `MemoryService` facade, `LongTermMemory` (reads/writes per-agent `MEMORY.md`), `MemoryTools` (`save_memory`, `recall_memory`) |
| `oryxos-tool` | Tool system: built-in tools (`FileTools`, `ShellTools`, `HttpTools`, `NotifyTools`), `McpClientService`, `McpToolAdapter`, `ToolRegistry`, `SandboxChecker` |
| `oryxos-channel-cli` | CLI channel: `CliChannel`, `oryxos chat` command implementation |
| `oryxos-web` | REST API: ten `ApiController` classes, `GlobalExceptionHandler`, the unified response envelope, OpenAPI spec, and the Vue admin console served at `/admin/` |
| `oryxos-storage` | Persistence: SQLite via Spring Data JPA — session, tool-invocation, LLM-call, scheduled-task, task-execution, memory-entry, notify-channel, and provider repositories |
| `oryxos-cli` | CLI entry point: Picocli main, 12 subcommands, `ConfigLoader` for credentials and config validation |
| `oryxos-boot` | Spring Boot bootstrap: main class, auto-configuration, dependency aggregation |

Modules communicate through interfaces. Adding a new Channel or Tool implementation means adding a new module — `oryxos-core` is not touched.

## Key Technical Decisions

| # | Decision | Choice | Reason |
| --- | --- | --- | --- |
| 1 | ReAct loop implementation | Self-implemented; does not use Spring AI's Agent abstractions | Full control over loop behavior; no hidden tool double-execution |
| 2 | Spring AI usage boundary | Protocol translation and `@Tool` JSON Schema generation only; automatic tool execution explicitly disabled | Spring AI's auto-execution would run tools twice — once by Spring AI, once by `ToolExecutor` |
| 3 | Execution model | Synchronous blocking with Java 21 virtual threads | Straightforward code, high concurrency without reactive programming complexity |
| 4 | Provider resolution | Dynamic `provider name → ChatModel` map backed by a SQLite registry, cached by `(name\|apiKey\|baseUrl)` | Explicit mapping (never Bean-type scanning) but runtime-mutable, so providers can be added or edited without a restart |
| 5 | HTTP service layer | Spring MVC + Java 21 virtual threads | Sync-style code, thousands of concurrent requests per node; `SseEmitter` available for streaming in extension phase |
| 6 | Sandbox strategy | Path/pattern allowlists at the application layer, runtime-manageable | `SecurityManager` is deprecated since JDK 17 and unavailable on JDK 21; full container-level sandbox is extension-phase work |
| 7 | Persistence | SQLite + Spring Data JPA for relational data; per-agent `MEMORY.md` for long-term memory | Single binary, no external database process required; audit tables written from day one so auditability is never retrofitted |

## Tech Stack

| Component | Choice |
| --- | --- |
| Language / runtime | Java 21 (required; virtual threads, no SecurityManager) |
| Framework | Spring Boot 3.x |
| LLM integration | Spring AI + Spring AI Alibaba (protocol translation and `@Tool` schema generation only) |
| HTTP service | Spring MVC with Java 21 virtual threads |
| Admin console | Vue 3 + Vite (served at `/admin/`) |
| CLI | Picocli |
| YAML parsing | SnakeYAML |
| Persistence | SQLite + Spring Data JPA (`schema.sql` maintained by hand; `CREATE TABLE IF NOT EXISTS`, no fragile SQLite `ALTER`) |
| Long-term memory | per-agent `MEMORY.md` flat file, keyword search; vector retrieval planned for extension phase |
| MCP integration | MCP Java SDK (MCP Client for connecting to external MCP servers) |
| Logging | Logback + SLF4J, structured JSON output |
| Build | Maven multi-module |
| Future: native binary | GraalVM Native Image (extension phase, reduces startup from ~3s to <100ms) |
| Future: observability | Micrometer + Prometheus (extension phase) |
