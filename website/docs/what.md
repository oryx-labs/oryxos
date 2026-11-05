# What is OryxOS

**OryxOS is an open-source, self-hosted Agent Harness OS for enterprise AI applications.** It is the operating foundation where enterprises run Agents in controlled context, environments, tools, permissions, and governance.

![OryxOS architecture overview](/images/oryxos-overview-en.svg)

Teams use OryxOS to build their own applications: engineering automation, knowledge support, ticket collaboration, operations diagnostics, and more. The application owns its business rules, data, integrations, and user experience. OryxOS provides the shared runtime beneath it.

## An enterprise harness, not a single Agent

Models and Agents will keep changing. Codex, Claude Code, Gemini, and in-house Agents should be replaceable executors. What an enterprise needs to retain is the environment in which those executors work:

- the business context and knowledge they may use;
- the systems, Tools, and permissions they may act through;
- the policy, approval, and sandbox boundaries they must respect;
- the acceptance criteria and evidence that prove a task is complete;
- the trace, cost, evaluation, and feedback that improve the next execution.

That is an **Agent Harness**. OryxOS makes it shared infrastructure rather than rebuilding it inside every application.

## The execution path

Applications compose the Agents, models, Knowledge, Tools, MCP servers, and Flows they need. But meaningful actions take a common, governed path:

```text
Task → Context / Environment → Identity / Authorization → Policy / Approval
     → Model / Tool Execution → Artifact / Evidence → Audit / Trace / Cost → State Update
```

Applications can compose capabilities freely; they cannot bypass governance. Governance is part of execution, not a report produced after it.

## What the OS provides

| Layer | Responsibility |
| --- | --- |
| **Task Runtime** | Lifecycle, state, sessions, artifacts, durable work, recovery, and human interaction. |
| **Context and environment** | Knowledge, memory, enterprise connections, and controlled execution environments. |
| **Capabilities** | Model routing, Tools, MCP, Skills, and extensible integrations. |
| **Governance** | Identity, authorization, policy, approval, sandbox boundaries, and audit. |
| **Operations** | Trace, evidence, cost attribution, evaluation, reliability, and feedback. |

## Current state

The single-node Runtime is available today: Agent definitions, model Providers, Memory, Knowledge, built-in Tools, MCP, Skills, scheduling, sandbox controls, audit records, REST APIs, and the Web Manager.

Durable Tasks, checkpoints and recovery, approvals, policy distribution, model routing and cost operations, evaluation, and distributed execution are evolving capabilities. See the [Roadmap](./roadmap).

## Why call it an OS

A model generates text. A harness gives an Agent context, tools, memory, execution, and boundaries. An **Agent Harness OS** gives many enterprise applications the same runtime contracts and governance path.

OryxOS is deliberately generic. A research-and-development Harness may add code repositories, native coding Agents, CI, browsers, and device testing. A support Harness may add CRM, ticketing, and human handoff. Those are applications built on OryxOS; the OS provides the common execution and control plane.
