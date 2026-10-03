# What is OryxOS

**A Distributed AI Agent OS — the foundation for running all kinds of agents.** OryxOS is an open-source Agent OS built in Java: one config file defines one agent; one foundation runs a fleet. Deploy privately, data never leaves your domain. Native integration with the MCP and A2A open protocols covers the five core capabilities: model access, reasoning loop, memory, tool calling, and external services. It lets a fleet of agents run and collaborate as reliably as a fleet of processes on an operating system.

## Vision

To become the runtime foundation of the agent era — where every business agent, cross-team agent, and cross-node agent in an enterprise runs, is managed, and collaborates on the same foundation. The long-term goal is to enter the Apache Software Foundation as a top-level project.

## Why OryxOS

Agents are already proven by mature open-source projects, and multi-agent orchestration frameworks flourish. But most existing solutions are built on Python and cloud-native stacks, shipped as development frameworks or hosted platforms. For enterprises where Java is the backend standard and private deployment is a compliance requirement, those solutions either mismatch the language stack, lock into a specific cloud, or remain experimental prototypes. In the Java ecosystem, a native, privately deployable, out-of-the-box agent foundation still has no mature open-source implementation.

The deeper judgment: **the bottleneck for agents working reliably in production is usually not the model — it's the agent's runtime environment.** Whether an agent can actually do work depends on whether it has a reliable foundation: the right context, controlled tools, isolated and auditable invocations, and message delivery that neither drops nor duplicates across nodes. OryxOS is not another agent — it is the foundation that lets a fleet of agents run and collaborate reliably.

## Agent Runtime vs. Agent OS

An agent runtime is the execution kernel that runs a single agent: model calls, tool execution, context management, loop control. An agent OS sits above the runtime and manages a fleet of agents: lifecycle, unified external channels and internal access, shared memory, multi-tenancy and governance, and cross-node collaboration in distributed form.

By analogy with operating systems: the runtime is like the execution environment of a single process; the agent OS is the layer that manages a fleet of processes, schedules resources, and provides shared services. In one sentence: the runtime makes one agent run; the agent OS makes a fleet of agents run and be managed. **OryxOS is the latter.**

## Five Core Capabilities

| Capability | Description |
| --- | --- |
| **LLM Access** | A provider abstraction unifies mainstream models. Agents are vendor-agnostic, switchable at runtime, no lock-in; local inference supported; multiple providers coexist via explicit mapping |
| **ReAct Loop** | The agent's reasoning engine, self-implemented — no external framework. The LLM decides whether and which tool to call; OryxOS executes and feeds results back until a final response or the iteration limit. Loop behavior fully controllable |
| **Memory** | Agents keep state across conversations. Two layers: session memory + long-term memory. Long-term memory is file-based with keyword retrieval; the interface reserves room for a vector-search upgrade |
| **Tool System** | Built-in file, shell, HTTP tools. Three extension tiers by rising effort: zero-code SKILL.md reusing existing MCP servers, light-code custom MCP servers, heavy-code native methods |
| **External Services** | Every capability exposed via REST API — business systems integrate over HTTP, in any language |

## Key Features

- 🤖 **Config as Agent** — one Profile defines one agent, no code; multiple agents coexist on one instance
- ☕ **Java Native** — Java / JDK 21, single executable JAR, fits your existing Java ops toolchain
- 🔒 **Private & Controlled** — runs on your own K8s, VMs, or bare metal; data never leaves the domain; no cloud lock-in
- 🛡️ **Security Isolation** — every tool call passes file/command/network whitelist checks, enforced sandbox isolation, credentials via enterprise key systems never on disk, full-chain audit
- 🧠 **Self-implemented ReAct** — the core reasoning loop is hand-written, not an external agent framework; fully controllable
- 🔌 **Open Standards** — MCP for tools, A2A for agent collaboration, SKILL.md for skills; interoperate, don't reinvent protocols
- 🧩 **Three-tier Tool Extension** — from zero-code SKILL.md to custom MCP servers to native methods
- 💾 **Cross-conversation Memory** — session + long-term memory so agents remember context
- 🌐 **Stateless & Scalable** — stateless instances with externalized state, architecting for distribution from day one

## Architecture at a Glance

![OryxOS system architecture](/images/architecture.svg)

The **ReAct loop** is the engine — self-implemented, not delegated to a framework:

![OryxOS ReAct loop](/images/react-loop.svg)

## Roadmap

Our philosophy: **slow is fast — restrained and focused.** Nail the single-node runtime kernel first, make running and managing a fleet of agents on one node genuinely usable, then grow distributed capabilities on top of it.

- **Phase 1 (current) — Single-node runtime kernel**: all five core capabilities working; single-node agent fleet usable
- **Phase 2 (planned) — Distributed foundation**: stateless nodes, externalized state, multi-replica deployment for scale and high availability
- **Phase 3 (vision) — Cross-node agent collaboration**: agent communication foundation, A2A integration, cross-node discovery, delegation, reliable async coordination
- **Horizontal capabilities (along the way)**: multi-tenancy, SSO, full audit, tool policies, observability, web console

## Design Principles

- **Foundation over agents** — the most important deliverable is not a powerful agent, but an environment where any agent runs reliably
- **Self-implemented core first** — the reasoning loop is hand-written; model protocol adaptation reuses mature libraries
- **Config is the agent** — an agent is defined by one config file, not by code
- **Open standards** — MCP for tools, A2A for collaboration, open formats for skills
- **Stateless instances, externalized state** — the prerequisite for a smooth path from single node to distributed
- **Security is the foundation, not a patch** — controlled tool sources, least privilege, enforced sandbox, credentials never on disk, full-chain audit
- **Restrained, phased delivery** — build the minimal complete kernel; governance and heavy distributed infrastructure only after real usage proves them necessary

## Project Info

- **Language**: Java (JDK 21)
- **License**: Apache 2.0
- **Community**: oryx-labs
- **Long-term goal**: enter the Apache Software Foundation as a top-level project
