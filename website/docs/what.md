# What is OryxOS

***OryxOS is an open-source, self-hosted Agent Harness OS for enterprise AI applications. Applications bring their business rules, data, systems, and user experience; OryxOS provides the shared runtime, governance, and operational optimization foundation for Agents and Tasks.***

![OryxOS architecture overview](/images/oryxos-overview-en.svg)

OryxOS is building one operating foundation where enterprises can **build, run, govern, and optimize** their own AI applications. Application teams compose Agents, models, Knowledge, Tools, MCP servers, and Flows to solve business problems; OryxOS supplies the shared execution semantics that make those applications extensible, governable, and operable across the organization.

**Agent Harness OS** describes the shared Runtime and governance semantics around every Agent and Task.

The principle is simple: **applications can freely compose capabilities, but cannot bypass governance.** Identity, authorization, policy, approval, model routing, Tool execution, audit, trace, cost attribution, and state updates all belong to the execution path.

## Where we are on that road

We distinguish the target platform model from what is available in the current runtime:

- **Available today**: define an Agent in **one sentence** or one markdown directory and run it on your own infrastructure with model providers, Memory, Knowledge, built-in Tools, MCP, Skills, notifications, scheduling, sandbox controls, audit records, REST APIs, and a Web Manager.
- **Evolving**: make a durable, governed **Task** the common unit for enterprise AI applications, with stronger checkpoint/recovery, approval, policy, model routing, cost optimization, evaluation, distributed-runtime, and Control Plane capabilities.
- **Exploratory**: optimize models, capabilities, quality, cost, and risk across applications; support governed multi-Agent collaboration and broad ecosystem interoperability. See the [Roadmap](./roadmap).

Every meaningful AI action should follow a consistent execution path:

```text
Task -> Identity -> Authorization -> Policy -> Approval
     -> Model / Tool Execution -> Audit / Trace / Cost
     -> Artifact / State Update
```

The path is the project direction. Individual capabilities are delivered incrementally and are labeled accordingly in this documentation.

## How applications use OryxOS

OryxOS gives applications a shared capability layer. An application chooses the Agents, models, knowledge, Tools, MCP servers, and Flows it needs; the platform supplies the Runtime and governance semantics around them.

This lets business teams build domain applications without rebuilding the underlying AI infrastructure. The same Runtime can serve R&amp;D automation, knowledge support, ticket collaboration, operations diagnostics, and other enterprise use cases.

One directory = one Agent remains a practical entry point. You write intent; the Runtime supplies capabilities, safety boundaries, and execution evidence.

Concretely, **one directory = one Agent**: a folder under `.oryxos/agents/<name>/` with an `AGENT.md` — YAML frontmatter as the agent's profile (identity, which LLM it talks to, which tools/skills it references) plus a body of task instructions. Drop the directory in and it goes live. Create, edit, and delete agents at runtime over the REST API or the web console — including generating a complete agent from a single sentence. Business systems integrate over HTTP. Data never leaves your infrastructure.

## What is an agent harness (and why an OS)

An **agent harness** is the scaffolding around a model that turns it into a working agent: the loop that drives reason → act → observe, the tools it can call and the execution that runs them, the context assembled before every LLM call, the memory it accumulates, the sandbox that contains it, and the audit trail that records what it did. A bare model only generates text — the harness is what lets it *do* things, reliably and safely.

An enterprise needs more than one Agent, but the platform must also give every application the same runtime and governance semantics. OryxOS is an **Agent Harness OS**: it gives every Agent a consistent harness and organizes Tasks across applications. Three layers — **Model → Harness → OS**:

| | Bare Model | Agent Harness | Agent Harness OS |
| --- | --- | --- | --- |
| Scope | A single LLM call | One reliable Agent | Enterprise applications and their Tasks |
| Provides | Text generation | Loop, tools + execution, context, memory, sandbox, audit, delivery | Lifecycle, channels, routing, shared registries, scheduling, governance, admin + API |
| Entry point | An API call to a model | A library or framework call | A deployable binary with a REST API |
| Multi-agent | Not in scope | Not in scope | An evolving capability built on shared Tasks and governance |
| Analogy | A CPU instruction | A process with its runtime | An OS running many processes |

A model generates text. A harness turns one model into one Agent that actually works. A Harness **OS** gives every Agent and application shared execution, capability, and governance semantics; multi-Agent teams are an evolving use case on top of that foundation.

## Core platform capabilities

### Model routing and cost optimization

OryxOS routes model use according to task requirements such as quality, latency, availability, cost, and data boundaries. Provider abstraction keeps applications independent of any one vendor, while a dynamic registry allows Providers to be added, changed, and switched at runtime. Fallback, budgets, quotas, and organization-wide cost optimization are evolving capabilities.

### ReAct Loop

A self-implemented reasoning engine — no external agent framework wrapping it. Each iteration: assemble the prompt (system prompt + bootstrap context + long-term memory + conversation history + available tools), call the LLM, inspect the response for tool calls, execute them under the sandbox, thread the results back as structured tool messages, repeat — until the LLM produces a final answer or the iteration limit is reached. The entire loop is a few dozen lines and fully inspectable; protocol differences between vendors are absorbed by an adapter layer, while tool execution stays entirely under OryxOS's control.

### Memory

Two layers today. Session memory holds the conversation history, persisted and recoverable across restarts. Long-term memory is **per-agent** — each agent writes to its own `MEMORY.md` via `save_memory` and searches it via `recall_memory`; every trigger is also auto-recorded to its archival memory. The memory is injected into every system prompt so agents keep context across conversations. When `embedding.*` is configured, semantic retrieval combines vector, keyword, and recency routes; broader shared memory backends and cross-application optimization remain on the [Roadmap](./roadmap).

### Tool System

**24 built-in tools** cover the baseline: file operations (read / write / list / append / move / copy / delete / mkdir), shell, HTTP (get / post / arbitrary request / fetch-webpage / download), time and JSON utilities, memory, and `notify`. All execute under sandbox enforcement — path whitelist for files, command whitelist for shell, and **split HTTP policy** (reads: default allow + SSRF blocklist; writes: domain wildcard whitelist) — with every invocation audited. The `notify` tool pushes to named channels (Feishu / WeCom / DingTalk / Slack / Discord / Telegram / WhatsApp / Teams / Google Chat / Mattermost / Matrix / generic webhook), themselves a dynamic registry with full CRUD.

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
- **Applications compose; governance cannot be bypassed** — models and Tools remain flexible, while policy, approval, audit, and operational evidence follow every meaningful action
- **Self-implement the core, reuse the plumbing** — the reasoning loop is written by hand; vendor protocol adapters are delegated to a mature adapter layer
- **One directory = one Agent** — an agent is defined by a directory of markdown, not by code
- **Open standards** — MCP for tools, A2A for agent-to-agent collaboration, open formats for skills
- **Stateless instances, externalized state** — the prerequisite for going distributed without an architectural rewrite
- **Security as foundation, not afterthought** — least privilege, mandatory sandbox whitelists, credentials via environment variables, full audit trail persisted from day one
- **Phased and disciplined** — build the minimal complete runtime kernel first; governance and distributed infrastructure come next, proven by real usage
