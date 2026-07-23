# Roadmap

OryxOS is built with a clear philosophy: **slow is smooth, smooth is fast**. Start with a solid single-node runtime kernel. Make it actually usable. Then grow distributed capabilities on top of it — step by step, each phase proven by real usage.

## Phase 1 — Single-node Runtime Kernel *(current)*

Get the five core capabilities working on a single node:

- One directory = one Agent: an `AGENT.md` directory, no code required — drop it in and it goes live
- Multi-agent coexistence: multiple agents running on the same instance
- Dynamic agent lifecycle: create / update / delete / invoke over REST, one-sentence draft generation, upload-to-live — *done*
- REST API: all capabilities exposed, responses in a unified envelope
- Web admin console at `/admin/`: agent management, schedules, sessions, tools, and runtime views — *done*
- Dynamic provider registry: SQLite-backed provider CRUD, re-key at runtime — *done*
- Notify channels: SQLite-backed CRUD (Feishu / WeCom / DingTalk / webhook) — *done*
- Scheduled tasks: per-agent schedules with management and execution history — *done*
- Workspace file browser over REST — *done*
- MCP integration: connect any community MCP server with zero code
- Full audit trail: `tool_invocations` and `llm_calls` written from day one

**Goal**: single-node running and managing a fleet of agents — actually usable, not a prototype.

## Phase 2 — Distributed Foundation *(planned)*

Make the runtime horizontally scalable:

- Stateless instances: no local state in the OryxOS process
- Externalized state: sessions, memory, and audit data stored outside the instance
- Multi-replica deployment: run multiple OryxOS instances behind a load balancer
- High availability: no single point of failure

## Phase 3 — Cross-node Agent Collaboration *(vision)*

Let agents on different nodes find, delegate to, and coordinate with each other:

- Integrate the A2A (Agent-to-Agent) open protocol
- Cross-node agent discovery and registration
- Reliable async task delegation between agents
- Message delivery guarantees across nodes

## Horizontal Capabilities *(added across all phases)*

These capabilities are built incrementally alongside the main phases:

| Capability | Status |
| --- | --- |
| Web management console | Done |
| Dynamic agent lifecycle (CRUD + upload-to-live) | Done |
| Provider registry CRUD | Done |
| Notify channels CRUD | Done |
| Scheduled task management | Done |
| Workspace file browser | Done |
| Multi-tenancy | Planned |
| SSO / authentication | Planned |
| Complete audit UI | Planned |
| Tool policies and RBAC | Planned |
| Observability (metrics, tracing) | Planned |
| SSE streaming responses | Planned |
| Episodic memory with vector search | Planned |
| WebSocket channel | Planned |
