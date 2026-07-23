# What is OryxOS

***A Java-native Agent Harness OS that gives every agent a production-grade harness and runs a fleet of them on your own infrastructure — shared channels, LLM routing, memory, tools, and auditable execution, all in one deployable binary.***

![OryxOS Architecture](/images/architecture.svg)

## What is an agent harness

An **agent harness** is the scaffolding around a model that turns it into a working agent: the loop that drives reason → act → observe, the tools it can call and the execution that runs them, the context assembled before every LLM call, the memory it accumulates, the sandbox that contains it, and the audit trail that records what it did. A bare model only generates text — the harness is what lets it *do* things, reliably and safely.

OryxOS is an **Agent Harness OS**: it gives every agent that same production-grade harness, and runs a fleet of them like an operating system runs processes. Think of it as three layers — **Model → Harness → OS**: the model is the brain; the harness is what makes one agent actually work; the OS is what runs many harnessed agents as a shared platform, adding lifecycle, channels, routing, and governance on top.

## What it is

OryxOS is a Spring Boot 3.x application that runs on JDK 21 as a unified Agent platform for enterprise deployments. **One directory = one Agent**: a folder under `.oryxos/agents/<name>/` with an `AGENT.md` (YAML frontmatter as the Agent's profile — identity, which LLM it talks to, which tools it can use — plus a body of task instructions) defines the Agent. Drop the directory in and it goes live; there is no separate `profiles/` directory. OryxOS handles everything else: the reasoning loop, context assembly, tool execution, sandbox enforcement, session persistence, and REST API exposure. Multiple Agents run inside a single instance simultaneously. You can create, edit, and delete Agents at runtime over the REST API or the web admin console — including generating a draft `AGENT.md` from a single sentence. Business systems integrate via HTTP. Data stays on your own infrastructure.

## Model, Harness, and Harness OS

Three layers are frequently conflated. They describe fundamentally different scopes.

| | Bare Model | Agent Harness | Agent Harness OS |
| --- | --- | --- | --- |
| Scope | A single LLM call | One reliable agent | A fleet of agents |
| Provides | Text generation | Loop, tools + execution, context, memory, sandbox, audit, delivery | Lifecycle, channels, routing, shared registries, scheduling, governance, admin + API |
| Entry point | An API call to a model | A library or framework call | A deployable binary with a REST API |
| Multi-agent | Not in scope | Not in scope | First-class: many Agents, shared capabilities, runtime lifecycle management |
| Analogy | A CPU instruction | A process with its runtime | An OS running many processes |

A model generates text. A harness turns one model into one agent that actually works. A Harness **OS** gives every agent the same harness and runs the whole fleet.

OryxOS *is* the harness — the self-implemented ReAct loop, tool execution, sandbox, context assembly, per-agent memory, and audit trail — and it is the OS layer above it: unified channel ingestion, shared registries, auditable invocation records, and a REST API any language can call.

## Five Core Capabilities

### LLM Routing

Provider abstraction over mainstream models: DeepSeek, Qwen, Kimi, Zhipu, Hunyuan, Doubao, Anthropic, OpenAI, and any OpenAI-protocol-compatible endpoint. Agents are provider-agnostic — the Agent declares which provider to use by name; the agent never knows which vendor is behind the call. Providers live in a dynamic, SQLite-backed registry with full CRUD (create/edit/delete over REST or the admin console) — seeded from config on first startup, then authoritative in the database, so you add or re-key a provider at runtime with no restart. Multiple providers co-exist via explicit name-to-`ChatModel` mapping, not bean scanning; the runtime resolves and caches the `ChatModel` by name. Local inference via Ollama or vLLM is supported.

### ReAct Loop

Self-implemented reasoning engine — no external Agent framework wrapping. Each iteration: assemble prompt (system prompt + bootstrap context + long-term memory + conversation history + available tools), call LLM, inspect response for tool calls, execute tools, thread the results back as structured tool messages, repeat. Tool calls and results flow through the provider as structured `tool_call`/`tool_result` messages (assistant tool calls plus tool responses carrying their ids), not flattened into plain text. Loop continues until the LLM produces a final response or the configured iteration limit is reached. The entire loop is a few dozen lines of Java and is fully inspectable. Spring AI is used only for LLM protocol translation — its automatic tool execution is explicitly disabled, so `ToolExecutor` owns execution.

### Memory

Two-layer memory in the core phase. Session memory holds the current conversation history, persisted to SQLite and recoverable across restarts. Long-term memory is **per-agent** — each Agent writes to its own `.oryxos/agents/<name>/MEMORY.md` (falling back to the global `.oryxos/memory/MEMORY.md` when there is no agent context) via `save_memory` and searches it via `recall_memory` (keyword matching); every trigger of an agent is also auto-recorded to its archival memory. The full file is injected into every system prompt so agents have persistent context across conversations. Files over 4,000 characters are truncated to stay within context limits. Vector retrieval is the planned upgrade path for the extension phase.

### Tool System

Built-in tools cover the baseline: `read_file`, `write_file`, `list_dir`, `shell`, `http_get`, `http_post`, `save_memory`, `recall_memory`, and `notify`. All execute with sandbox enforcement — path allowlist for files, command allowlist for shell, domain allowlist for HTTP. The `notify` tool pushes to a notify channel referenced by name; channels (Feishu / WeCom / DingTalk / generic webhook adapters) live in their own dynamic, SQLite-backed registry with full CRUD.

Extension follows three tiers, ordered by effort:

| Tier | Effort | Approach |
| --- | --- | --- |
| Zero-code | Lowest | Write a `SKILL.md` describing the task, reference existing community MCP servers in Profile |
| Light-code | Medium | Write an MCP server in any language; OryxOS connects as MCP Client |
| Heavy-code | Highest | Annotate a Spring Bean with `@Tool`; registers directly in-process |

All tools — built-in, MCP-backed, and native — are registered through `ToolRegistry` and expose a uniform `OryxTool` interface to the ReAct loop.

### REST API

A REST API under `/api/v1` exposes all capabilities to external systems: dynamic agent lifecycle (create / list / get / update / delete / invoke, plus per-agent memory, console session, and one-sentence file generation), session lifecycle management, provider and notify-channel CRUD, schedule management, sandbox whitelist management, a workspace file browser, profile listing, tool inventory, health check, and runtime info. Every response is wrapped in a unified envelope (`{ code, message, data, timestamp }`). Any language that can send HTTP requests can integrate. No SDK required for the core phase. The Web Service is the integration boundary — business systems plug in here, not at the library level.

### Web Admin Console

A Vue 3 + Vite single-page console is served at `/admin/`, styled to match this site. It gives a visual front end over the REST API: an overview dashboard, an Agent list with per-row immediate trigger / detail / delete and an Agent detail view (基本信息 / 生成 / 文件 / 会话 / 记忆 tabs, including the one-sentence generation flow), schedules management, and an "OS runtime" group covering sessions, providers (full CRUD), tools, notify channels (full CRUD), and the sandbox whitelist.

## Design Principles

- **Platform before Agent** — the most important deliverable is the environment that lets any agent run reliably, not any particular agent
- **Self-implement the core, reuse the plumbing** — the reasoning loop is written by hand; LLM protocol adapters delegate to Spring AI Alibaba
- **One directory = one Agent** — an Agent is defined by a directory with an `AGENT.md` (frontmatter profile + body instructions), optional `skills/` and `scripts/`, not by code
- **Open standards** — MCP for tools, A2A for agent-to-agent collaboration, `SKILL.md` files for skills
- **Stateless instances, externalized state** — the prerequisite for eventually going distributed without an architectural rewrite
- **Security as foundation, not afterthought** — tool source control, least privilege, mandatory sandbox allowlists, credentials via environment variables, full audit trail written to SQLite from day one
- **Phased and disciplined** — build the minimal complete runtime kernel first; governance and distributed infrastructure come later, proven by real usage data
