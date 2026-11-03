# Why OryxOS

## The hard part starts after an Agent can write an answer

It is increasingly easy to connect a model to a prompt and a few Tools. The hard work begins when that Agent needs to act inside a company: read internal knowledge, touch production systems, operate under a budget, prove what it did, and improve without creating a different stack for every application.

That work does not belong inside every application. It belongs in a shared Harness.

## What companies need to control

An enterprise AI application needs more than a capable model:

- **Context** — the right business knowledge, current data, and task history;
- **Environment** — a controlled place to execute work and connect to enterprise systems;
- **Capability** — Models, Tools, MCP servers, Skills, and credentials that can be extended without being exposed without limit;
- **Governance** — identity, authorization, policy, approval, sandboxing, audit, and cost boundaries;
- **Proof and learning** — artifacts, acceptance evidence, trace, evaluation, and feedback.

When every team implements these alone, the enterprise gets a collection of demos and scripts. Rules drift, costs are opaque, and an incident in one application teaches nothing to the next.

## OryxOS makes the shared layer explicit

OryxOS is the open-source, self-hosted Agent Harness OS for that layer. It lets teams focus on the business application while the Runtime handles the common execution path: Task lifecycle, governed capabilities, model routing, cost attribution, audit, and operations.

The point is not to replace the best Agent. It is to make any Agent useful within an enterprise, without making the enterprise surrender control of its data, tools, and operating rules.

## A common foundation, different Harnesses

The same OS can support different applications because the business layer stays outside the kernel:

| Application Harness | Adds on top of OryxOS |
| --- | --- |
| Engineering | Code repositories, Coding Agents, CI, review, and test evidence |
| Support | Knowledge, CRM, ticketing, approval, and human handoff |
| Operations | Runbooks, observability, controlled actions, and incident evidence |

OryxOS supplies the Runtime and governance semantics they share. Each Harness owns the business workflow and acceptance standard that makes it useful.

## The direction

The current Runtime is a starting point, not the full destination. OryxOS is evolving durable Tasks, recovery, approval, policy, model routing, cost operations, evaluation, and distributed execution in public.

The direction is straightforward: enterprises should be able to build their own AI applications, use the best available Agents, and govern every meaningful action through one extensible operating foundation.
