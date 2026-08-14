# What is OryxOS

***OryxOS is the self-hosted Agent Operating System for the enterprise. Describe a task in plain language — OryxOS breaks it down, assembles a team of agents, and they collaborate to deliver the result. So every company can run its own agents, in plain language.***

![one sentence → decompose → assemble an agent team → collaborate → deliver the result](/images/pipeline-en.svg)

![OryxOS Architecture](/images/architecture.svg)

## Where we are on that road

We say exactly what's shipped and what's ahead — no hand-waving:

- **Shipped today**: define an agent in **one sentence** (or one markdown directory) and it goes live instantly — no restart, no code. It comes with memory, 24 built-in tools, MCP connectors, a global skill library, notifications, cron scheduling, sandboxed execution, and a full audit trail — running entirely on your own infrastructure.
- **The north star** (in active design — see the [Roadmap](./roadmap)): publish a task in one sentence and an agent **team** self-organizes to deliver it — a planner decomposes, specialists work in parallel, a reviewer merges and checks — every step audited, with humans approving the dangerous ones.

## The Agent formula

Everything an agent needs is **built into the OS**. An agent is just a declaration of intent that references those capabilities by name:

![natural language + Memory + Tools + MCP + Skills + Knowledge + Notify = a working Agent](/images/agent-formula-en.svg)

This is the whole point of OryxOS: **drive the cost of defining an agent toward zero.** You write what you want; the OS supplies the capabilities, the safety rails, and the audit trail.

Concretely, **one directory = one Agent**: a folder under `.oryxos/agents/<name>/` with an `AGENT.md` — YAML frontmatter as the agent's profile (identity, which LLM it talks to, which tools/skills it references) plus a body of task instructions. Drop the directory in and it goes live. Create, edit, and delete agents at runtime over the REST API or the web console — including generating a complete agent from a single sentence. Business systems integrate over HTTP. Data never leaves your infrastructure.

## What is an agent harness (and why an OS)

An **agent harness** is the scaffolding around a model that turns it into a working agent: the loop that drives reason → act → observe, the tools it can call and the execution that runs them, the context assembled before every LLM call, the memory it accumulates, the sandbox that contains it, and the audit trail that records what it did. A bare model only generates text — the harness is what lets it *do* things, reliably and safely.

An enterprise never runs just one agent. OryxOS is an **Agent Harness OS**: it gives every agent the same production-grade harness, and runs the whole fleet like an operating system runs processes. Three layers — **Model → Harness → OS**:

| | Bare Model | Agent Harness | Agent Harness OS |
| --- | --- | --- | --- |
| Scope | A single LLM call | One reliable agent | A fleet of agents |
| Provides | Text generation | Loop, tools + execution, context, memory, sandbox, audit, delivery | Lifecycle, channels, routing, shared registries, scheduling, governance, admin + API |
| Entry point | An API call to a model | A library or framework call | A deployable binary with a REST API |
| Multi-agent | Not in scope | Not in scope | First-class: many agents, shared capabilities, runtime lifecycle management |
| Analogy | A CPU instruction | A process with its runtime | An OS running many processes |

A model generates text. A harness turns one model into one agent that actually works. A Harness **OS** gives every agent the same harness and runs the whole fleet — and it's the layer where an agent *team* becomes possible.

## Five Core Capabilities

### LLM Routing

Provider abstraction over mainstream models: DeepSeek, Qwen, Kimi, Zhipu, Hunyuan, Doubao, Anthropic, OpenAI, and any OpenAI-protocol-compatible endpoint. Agents are provider-agnostic — an agent declares which provider to use by name and never knows which vendor is behind the call. Providers live in a dynamic registry with full CRUD (over REST or the console) — seeded from config on first startup, then authoritative in the database, so you add or re-key a provider at runtime with no restart. Multiple providers co-exist via explicit name-to-model routing, and local inference via Ollama or vLLM is supported.

### ReAct Loop

A self-implemented reasoning engine — no external agent framework wrapping it. Each iteration: assemble the prompt (system prompt + bootstrap context + long-term memory + conversation history + available tools), call the LLM, inspect the response for tool calls, execute them under the sandbox, thread the results back as structured tool messages, repeat — until the LLM produces a final answer or the iteration limit is reached. The entire loop is a few dozen lines and fully inspectable; protocol differences between vendors are absorbed by an adapter layer, while tool execution stays entirely under OryxOS's control.

### Memory

Two layers today. Session memory holds the conversation history, persisted and recoverable across restarts. Long-term memory is **per-agent** — each agent writes to its own `MEMORY.md` via `save_memory` and searches it via `recall_memory`; every trigger is also auto-recorded to its archival memory. The memory is injected into every system prompt so agents keep context across conversations. The upgrade path — semantic, vector-backed memory plus a built-in knowledge base — is a headline item on the [Roadmap](./roadmap).

### Tool System

**24 built-in tools** cover the baseline: file operations (read / write / list / append / move / copy / delete / mkdir), shell, HTTP (get / post / arbitrary request / fetch-webpage / download), time and JSON utilities, memory, and `notify`. All execute under sandbox enforcement — path whitelist for files, command whitelist for shell, domain whitelist for HTTP — with every invocation audited. The `notify` tool pushes to named channels (Feishu / WeCom / DingTalk / generic webhook), themselves a dynamic registry with full CRUD.

Extension follows three tiers, ordered by effort:

| Tier | Effort | Approach |
| --- | --- | --- |
| Zero-code | Lowest | Write a `SKILL.md` describing the task, reference existing community MCP servers |
| Light-code | Medium | Write an MCP server in any language; OryxOS connects as MCP Client |
| Heavy-code | Highest | Write a native in-process tool and register it directly |

All tools — built-in, MCP-backed, and native — register through one `ToolRegistry` and expose a uniform interface to the ReAct loop.

### REST API & Web Console

A REST API under `/api/v1` exposes everything to external systems: dynamic agent lifecycle (create / list / get / update / delete / invoke, plus per-agent memory, console session, and one-sentence generation), sessions, provider and notify-channel CRUD, schedules, sandbox whitelist management, a workspace file browser, tool inventory, health, and runtime info — all wrapped in one response envelope. Any language that speaks HTTP integrates; no SDK required.

On top of it sits a web console at `/admin/`: an overview dashboard, agent management with the one-sentence creation flow, file browser with markdown rendering, skills, schedules, sessions, providers, notify channels, and the sandbox whitelist.

## Design Principles

- **Platform before Agent** — the most important deliverable is the environment that lets any agent run reliably, not any particular agent
- **Self-implement the core, reuse the plumbing** — the reasoning loop is written by hand; vendor protocol adapters are delegated to a mature adapter layer
- **One directory = one Agent** — an agent is defined by a directory of markdown, not by code
- **Open standards** — MCP for tools, A2A for agent-to-agent collaboration, open formats for skills
- **Stateless instances, externalized state** — the prerequisite for going distributed without an architectural rewrite
- **Security as foundation, not afterthought** — least privilege, mandatory sandbox whitelists, credentials via environment variables, full audit trail persisted from day one
- **Phased and disciplined** — build the minimal complete runtime kernel first; governance and distributed infrastructure come next, proven by real usage
