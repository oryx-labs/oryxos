# What is OryxOS

OryxOS is an open-source **Distributed AI Agent OS** built on Java 21. One config defines one Agent; one platform runs a fleet. Deploy it on your own K8s or servers — agents run and collaborate like processes on an OS, sharing channels, LLM routing, tools, memory, and sandboxed execution.

## Agent OS vs Agent Runtime

The Agent ecosystem already has mature runtimes. OryxOS is something different.

An **Agent Runtime** is the execution kernel that runs a single agent: it calls the model, executes tools, manages context, and controls the reasoning loop.

An **Agent OS** sits above the runtime and manages a *fleet* of agents: multiple agent lifecycles, unified inbound channels, unified memory, multi-tenancy and governance, and cross-node coordination.

To borrow the OS analogy — a runtime is like a single process's execution environment. An Agent OS is the layer that manages a group of processes, schedules resources, and provides shared services. A runtime gets one agent running. An Agent OS gets a fleet of agents running and managed.

**OryxOS is the latter.**

## Why Java

The Agent ecosystem is almost entirely Python-based or cloud-coupled. For enterprises where Java is the backend standard and private deployment is a compliance requirement, there is no native, production-ready Agent OS in the Java ecosystem. OryxOS fills this gap.

More fundamentally: the bottleneck for reliable agents in production is not the model — it's the runtime environment. Whether an agent can actually work depends on having the right context, controlled tools, isolated and auditable execution, and reliable message delivery for cross-node coordination. OryxOS is not another agent. It is the foundation that lets a fleet of agents run reliably.

## Five Core Capabilities

**LLM Routing**
Provider abstraction unifies mainstream models. Agents are provider-agnostic — they never care which vendor is behind the call. Switch at runtime with zero code change via a single line in Profile YAML. Local inference (Ollama) supported. Multiple providers co-exist via explicit mapping.

**ReAct Loop**
Self-implemented reasoning engine — no external Agent framework. The LLM decides whether and which tool to call; OryxOS executes it, feeds the result back; the LLM decides the next step. This continues until a final response is produced or the iteration limit is reached. Loop behavior is fully controllable.

**Memory**
Cross-conversation state persistence. Session memory keeps the current conversation; long-term memory (`MEMORY.md`) persists facts and preferences, is keyword-searchable, and is automatically injected into every system prompt. Vector retrieval is the planned upgrade path.

**Tool System**
Built-in file, shell, and HTTP tools cover the common cases. Three-tier extension for everything else:

- Zero-code: write a `SKILL.md` and reuse a community MCP server
- Light-code: write your own MCP server in any language
- Heavy-code: register a Spring `@Tool` bean for in-process execution

**REST API**
All capabilities are exposed via REST. Any language can integrate. Business systems connect via HTTP. No SDK required.

## Design Principles

- **Platform before Agent** — the most important deliverable is the environment that lets any agent run reliably, not any specific agent
- **Self-implement the core** — reasoning loop is self-implemented; protocol adapters reuse mature libraries
- **Config = Agent** — an agent is defined by configuration, not code
- **Open standards** — MCP for tools, A2A for agent-to-agent collaboration, open formats for skills
- **Stateless instances** — state is externalized from the start; the prerequisite for scaling to distributed
- **Security as foundation** — controlled tool sources, least privilege, mandatory sandbox, credentials never persisted, full audit trail from day one
- **Phased and disciplined** — build the minimal complete runtime kernel first; every architecture upgrade is proven by real usage data
