# Roadmap

OryxOS is built with a clear philosophy: **stable runtime semantics, open extensions, and incremental proof**. Start with a usable single-node Agent Runtime. Then add durable Task, governance, and distributed capabilities step by step, with each phase verified by real usage.

The roadmap distinguishes three statuses:

- **Available** — implemented and usable in the current repository.
- **Evolving** — actively being extended or made consistent across applications and deployments.
- **Exploratory** — longer-term direction that still needs design, validation, or community agreement.

## Available — Single-node Runtime Kernel

The current kernel provides the base Agent Runtime and its extension points:

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

**Boundary**: this is a usable single-node Agent Runtime, not the complete enterprise Control Plane. Durable Task lifecycle, broad governance workflows, and cross-application optimization remain separate roadmap work.

## Evolving — Task Runtime and Control Plane

Make enterprise AI work durable, governed, and operable across applications:

- Durable Task lifecycle: queued, running, waiting for approval, completed, failed, cancelled
- Checkpoint, retry, idempotency, cancellation, recovery, and Artifact semantics
- Identity context, authorization, Tool Policy, approval gates, and auditable decisions
- Trace, Metrics, Evaluation, cost attribution, budgets, and regression feedback
- Stable Runtime APIs and extension contracts for applications and enterprise systems

## Evolving — Distributed Foundation

Make the runtime horizontally scalable:

- Stateless instances: no local state in the OryxOS process
- Externalized state: sessions, memory, and audit data stored outside the instance
- Multi-replica deployment: run multiple OryxOS instances behind a load balancer
- High availability: no single point of failure

## Exploratory — Cross-application and Agent Collaboration

Use stable Task and governance semantics to support broader platform optimization and multi-Agent collaboration:

- Cross-application model and capability optimization
- Shared evaluation sets, cost models, policy feedback, and operational learning
- Governed planner, specialist, reviewer, and A2A collaboration
- Cross-node Agent discovery, delegation, and reliable message delivery
- Portable extensions across runtimes, identity systems, and execution hosts

## Horizontal Capabilities *(added across all phases)*

These capabilities are built incrementally alongside the main phases:

| Capability | Status |
| --- | --- |
| Web management console | Available |
| Dynamic Agent lifecycle (CRUD + upload-to-live) | Available |
| Provider registry CRUD | Available |
| Notify channels CRUD | Available |
| Scheduled Task management | Available |
| Workspace file browser | Available |
| Multi-tenancy | Evolving (organization/team ACLs; broader tenancy deferred) |
| SSO / authentication | Evolving (OIDC, default off) |
| Complete audit UI | Available (016 reports) |
| Tool policies and RBAC | Evolving (020 + 039, default off) |
| Observability (metrics, tracing) | Available (OTel/OTLP) |
| SSE streaming responses | Available (019) |
| Episodic memory with vector search | Evolving (015 semantic recall) |
| Durable Task and approval lifecycle | Evolving |
| Cost and evaluation operations | Evolving |
| WebSocket channel | Exploratory |
