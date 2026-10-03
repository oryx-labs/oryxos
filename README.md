<p align="center">
  <img src="docs/images/logo.svg" alt="OryxOS" width="256"/>
</p>

<p align="center">
  <strong>Distributed AI Agent OS — let agents run and collaborate like processes on an OS</strong>
</p>

<p align="center">
  <a href="https://github.com/xkmeng/oryxos/actions"><img src="https://img.shields.io/badge/build-maven-blue?style=flat-square&logo=apachemaven" alt="Build"/></a>
  <a href="https://www.java.com"><img src="https://img.shields.io/badge/Java-21-007396?style=flat-square&logo=openjdk&logoColor=white" alt="Java 21"/></a>
  <a href="https://spring.io/projects/spring-boot"><img src="https://img.shields.io/badge/Spring%20Boot-3.x-6DB33F?style=flat-square&logo=springboot&logoColor=white" alt="Spring Boot 3"/></a>
  <a href="https://modelcontextprotocol.io"><img src="https://img.shields.io/badge/MCP-compatible-8A2BE2?style=flat-square" alt="MCP"/></a>
  <a href="https://www.apache.org/licenses/LICENSE-2.0"><img src="https://img.shields.io/badge/license-Apache%202.0-blue?style=flat-square" alt="Apache 2.0"/></a>
</p>

<p align="center">
  <a href="#readme">English</a> · <a href="docs/oryxos.md">中文简介</a>
</p>

---

OryxOS is an open-source **Distributed AI Agent OS** built on Java 21. One config file defines one Agent; one platform runs a fleet. Deploy privately on your own Kubernetes or servers — agents run and collaborate like processes on an OS, sharing channel access, LLM routing, tool calling, memory, and sandboxed execution. Your data never leaves your infrastructure.

> **Long-term vision:** enter the Apache Software Foundation as a top-level project.

## Why OryxOS

The open-source agent ecosystem already has mature runtimes — but they are almost all Python- or Node.js-based, and positioned for individuals or small teams. For enterprises where Java is the backend standard and private deployment is a hard compliance requirement, there is no native, production-ready Agent OS in the Java ecosystem. OryxOS fills that gap.

More fundamentally: **the bottleneck for reliable agents in production is not the model — it's the runtime environment.** Whether an agent can actually do work depends on whether it has reliable access to the right context, controlled tools, isolated and auditable execution, and dependable message delivery. OryxOS is not another agent — it is the OS-level foundation that lets a fleet of agents run reliably.

### Agent OS vs. Agent Runtime

| | Agent Runtime | Agent OS |
| --- | --- | --- |
| Scope | Runs **one** agent | Runs and manages a **fleet** of agents |
| Analogy | The execution environment of a single process | The OS layer managing processes, scheduling resources, providing shared services |
| Provides | Model calls, tool execution, context management, loop control | Lifecycle, unified channels, shared memory, multi-tenancy, audit, cross-node coordination |

OryxOS is the latter.

## Key Features

- 🤖 **Config = Agent** — one Profile YAML defines one Agent, no code required. Multiple agents coexist on a single instance.
- ☕ **Java Native** — Java 21 + virtual threads. Single executable JAR. Plugs into your existing Java ops toolchain — no Python, no Node.js.
- 🔒 **Private & Compliant** — runs on your own K8s, VMs, or bare metal. Data never leaves your environment. No cloud lock-in. Credentials come from your environment variables / enterprise key management — never written to config files.
- 🛡️ **Security as Foundation** — every tool call passes path / command / domain whitelist checks. Full audit trail from day one: every tool invocation and LLM call is persisted to the audit database, not just logged.
- 🧠 **Self-implemented ReAct Loop** — the core reasoning engine is hand-written, not delegated to a framework. Loop behavior is fully under your control.
- 🔌 **Open Standards** — tools via [MCP](https://modelcontextprotocol.io), agent collaboration via A2A, skills via SKILL.md. Interoperates with the ecosystem instead of inventing new protocols.
- 🧩 **Three-tier Tool Extension** — from zero-code SKILL.md to custom MCP servers to native `@Tool` Java beans. Pick the entry barrier that fits.
- 💾 **Cross-conversation Memory** — session memory + long-term memory (MEMORY.md), so agents remember preferences, projects, and decisions across conversations.
- 🌐 **Stateless by Design** — instances hold no irreplaceable state; state is externalized. The prerequisite for scaling to a distributed deployment without redesign.

## Architecture

<p align="center">
  <img src="docs/images/architecture.svg" alt="OryxOS Architecture" width="100%"/>
</p>

> 🖱️ Prefer an interactive version? Open [docs/images/architecture.html](docs/images/architecture.html) — a self-contained page with theme switching, pan/zoom, and guided views (ReAct main path / tool execution chain / state & audit).

OryxOS is a single Spring Boot application with two entry points — the CLI channel and the REST API — both feeding the same engine. The **ReAct loop** is the engine: it assembles the prompt, calls the LLM via a Provider, executes tools through the sandbox-checked tool layer, feeds results back, and repeats until a final answer (or the iteration limit). Underneath, sessions and audit data land in SQLite; profiles, bootstrap files, memory, and skills live on the filesystem so you can edit them directly and track them in git.

<p align="center">
  <img src="docs/images/react-loop.svg" alt="ReAct Loop" width="640"/>
</p>

## Five Core Capabilities

| Capability | What it gives you |
| --- | --- |
| **LLM Routing** | Provider abstraction unifies mainstream models (DeepSeek, Qwen, Kimi, OpenAI, Anthropic, Ollama, …). Agents are provider-agnostic; switch models via config, zero code change. |
| **ReAct Loop** | Self-implemented reasoning engine. The LLM decides whether and which tool to call; OryxOS executes it and feeds the result back, until a final response. Max iterations configurable per profile. |
| **Memory** | Session memory + long-term memory. MEMORY.md is injected into every system prompt; agents read/write it through `save_memory` / `recall_memory` tools. Keyword retrieval today, with the interface reserved for a vector-search upgrade. |
| **Tool System** | 8 built-in tools (file, shell, HTTP, memory). Extend three ways: SKILL.md + existing MCP server (zero code), custom MCP server (any language), or Java `@Tool` beans (in-process). |
| **REST API** | Every capability exposed over HTTP at `/api/v1`. Any language that can send an HTTP request can integrate. |

## Quick Start

**Prerequisites:** Java 21, Maven 3.9+, an LLM API key (e.g. DeepSeek) or a local inference endpoint (Ollama / vLLM).

```bash
# 1. Build
git clone https://github.com/xkmeng/oryxos.git
cd oryxos
mvn package -DskipTests

# 2. Initialize the workspace (creates .oryxos/ in the current directory)
java -jar oryxos-boot/target/oryxos-boot-*.jar init

# 3. Provide your LLM API key
export DEEPSEEK_API_KEY=your-key-here

# 4. Chat with your agent
java -jar oryxos-boot/target/oryxos-boot-*.jar chat --profile default

# Or expose the REST API instead
java -jar oryxos-boot/target/oryxos-boot-*.jar serve --port 8080
```

## Defining an Agent

Every agent is a single YAML file under `.oryxos/profiles/` — no code:

```yaml
name: ops-agent
description: DevOps assistant
identity:
  agent_name: ops-agent
  prompt: You are a professional DevOps assistant...
provider:
  name: deepseek          # switch to qwen / kimi / ollama — zero code change
  model: deepseek-chat
  temperature: 0.7
  api_key: ${DEEPSEEK_API_KEY}   # injected via environment variable
tools:
  - shell
  - read_file
  - http_get
  - save_memory
  - recall_memory
skills:
  - daily-pr-digest       # → .oryxos/skills/daily-pr-digest.md
mcp_servers:
  - github-mcp
bootstrap:
  - AGENTS.md             # project-level agent guidance
  - SOUL.md               # agent personality
  - USER.md               # user preferences (read-only)
settings:
  max_iterations: 10
  max_history_turns: 20
```

## Built-in Tools

| Tool | Class | Description |
| --- | --- | --- |
| `read_file` | `FileTools` | Read a file (path whitelist) |
| `write_file` | `FileTools` | Write a file (path whitelist) |
| `list_dir` | `FileTools` | List a directory (path whitelist) |
| `shell` | `ShellTools` | Run a bash command (command whitelist + timeout) |
| `http_get` | `HttpTools` | HTTP GET (domain whitelist) |
| `http_post` | `HttpTools` | HTTP POST (domain whitelist) |
| `save_memory` | `MemoryTools` | Append to long-term memory (MEMORY.md) |
| `recall_memory` | `MemoryTools` | Keyword search over MEMORY.md |

## Extending OryxOS

Three tiers, pick the lowest barrier that works:

| Tier | Effort | How | Best for |
| --- | --- | --- | --- |
| **Zero code** ⭐ | Lowest | Write a SKILL.md describing the task + reuse community MCP servers | Most scenarios — the LLM composes existing tools itself |
| **Light code** | Medium | Write an MCP server in any language, register it in `mcp_servers.yaml` | Connecting your ERP, CRM, internal systems |
| **Heavy code** | Highest | Annotate Java methods with `@Tool`; runs in-process | Deep integration, best performance |

## REST API

All endpoints are prefixed with `/api/v1`:

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/sessions` | Create a session |
| `POST` | `/sessions/{id}/messages` | Send a message (triggers the ReAct loop) |
| `GET` | `/sessions/{id}` | Get session history |
| `DELETE` | `/sessions/{id}` | Archive a session |
| `POST` | `/agents/{name}/invoke` | Stateless one-shot agent invocation |
| `GET` | `/profiles` | List all profiles |
| `GET` | `/memory` | Read long-term memory |
| `GET` | `/tools` | List available tools |
| `GET` | `/health` | Health check |
| `GET` | `/info` | Runtime info + provider status |

## CLI

```bash
oryxos init                      # Initialize the .oryxos/ workspace
oryxos status                    # Show config and runtime status
oryxos chat [--profile <name>]   # Interactive multi-turn chat
oryxos serve [--port 8080]       # Start the REST API server
oryxos gateway                   # Daemon mode (multiple channels)

oryxos profile list | create | show | delete
oryxos provider list
oryxos tool list
oryxos session list
```

## Tech Stack

| Component | Choice |
| --- | --- |
| Language / Runtime | Java 21 (virtual threads) |
| Framework | Spring Boot 3.x |
| LLM Integration | Spring AI Alibaba (protocol translation + `@Tool` schema generation only) |
| HTTP | Spring MVC + Java 21 virtual threads |
| CLI | Picocli |
| YAML | SnakeYAML |
| Persistence | SQLite + Spring Data JPA |
| Logging | Logback + SLF4J (structured JSON) |
| Build | Maven multi-module |

## Module Structure

```text
oryxos/
├── oryxos-core          # OryxTool interface, Session, ReActLoop, PromptBuilder, ToolExecutor
├── oryxos-provider      # ProviderService, Function Calling adapter, explicit multi-provider map
├── oryxos-memory        # MemoryService, LongTermMemory, MemoryTools (save/recall)
├── oryxos-tool          # Built-in tools (file/shell/http), MCP Client, ToolRegistry, SandboxChecker
├── oryxos-channel-cli   # CLI channel: oryxos chat implementation
├── oryxos-web           # REST controllers, GlobalExceptionHandler, OpenAPI docs
├── oryxos-storage       # SQLite repositories: sessions, tool_invocations, llm_calls
├── oryxos-cli           # Picocli entry, 12 subcommands, ConfigLoader
└── oryxos-boot          # Spring Boot main class, auto-configuration, dependency aggregation
```

Modules are decoupled through interfaces. Adding a new Channel or Tool requires only a new module — `oryxos-core` stays untouched.

## How OryxOS Compares

| | **OryxOS** | Agent frameworks (LangChain, Spring AI, …) | Orchestration platforms (Dify, Coze, …) |
| --- | --- | --- | --- |
| Deliverable | A self-hosted runtime that agents live in | Libraries / SDKs — you write code and run it yourself | Visual workflows built by dragging nodes |
| Users | Business teams configure agents, developers write tools | Developers | Business users / developers |
| Deployment | Your own machines, private, open source | Your own runtime environment | Vendor cloud or self-hosted platform |

They are complementary, not competing: OryxOS *uses* frameworks (Spring AI for LLM protocol translation) internally, and orchestration platforms can run *on top of* OryxOS's API.

## Roadmap

> Our philosophy: **slow is fast — restrained and focused.** Nail the single-node runtime kernel first, make running and managing a fleet of agents on one node genuinely usable, then grow distributed capabilities on top of it. Distributed is the destination, but engineering goes single-node first, one solid step at a time.

**Phase 1 — Single-node Runtime Kernel** *(current)*
Five core capabilities operational: config-as-agent, multi-agent coexistence, REST API, MCP integration. Goal: single-node running and managing a fleet of agents — actually usable.

**Phase 2 — Distributed Foundation** *(planned)*
Stateless instances, externalized state (Redis / PostgreSQL / object storage), multi-replica deployment. Larger scale and high availability.

**Phase 3 — Cross-node Agent Collaboration** *(vision)*
Agent communication infrastructure, A2A protocol integration. Cross-node agent discovery, delegation, and reliable asynchronous coordination.

*Horizontal capabilities land across phases: multi-tenancy, SSO, full audit, tool policies, observability, web management console.*

## Design Principles

- **Foundation over agents** — the most important deliverable is not a powerful agent, but an environment where any agent runs reliably
- **Self-implemented core, controlled by us** — the reasoning loop is hand-written; mature libraries are reused only for protocol adaptation, not reinvented
- **Config is the agent** — an agent is defined by one config file, not by code
- **Open standards** — tools via MCP, collaboration via A2A, skills via SKILL.md; interoperate with the ecosystem instead of inventing protocols
- **Stateless instances, externalized state** — the prerequisite for going distributed without redesign
- **Security is the foundation, not a patch** — controlled tool sources, least privilege, enforced sandbox whitelists, credentials never on disk, full audit from day one
- **Restrained, phased delivery** — build the minimal complete kernel now; governance and heavy distributed infrastructure must be proven necessary by real usage data first

## Documentation

> 📝 Project documentation is currently written in Chinese.

| Document | Description |
| --- | --- |
| [docs/oryxos.md](docs/oryxos.md) | Project overview: vision, roadmap, design principles |
| [docs/DemandAnalysis.md](docs/DemandAnalysis.md) | Requirements: capabilities, scope tiers, acceptance demos |
| [docs/TechnicalSolution.md](docs/TechnicalSolution.md) | Technical solution: key decisions, modules, data model |
| [docs/IndustryResearch.md](docs/IndustryResearch.md) | Industry research: the Agent OS landscape and the Java gap |
| [docs/AiProgrammingGuide.md](docs/AiProgrammingGuide.md) | AI-assisted development guide: how this project is built |

## Contributing

Contributions are welcome! OryxOS is developed AI-coding-first — and works just as well with a traditional workflow:

1. Fork the repository and create your branch from `main`
2. Make your changes (with tests, where applicable)
3. `mvn verify` passes
4. Open a Pull Request with a clear description

Good first issues are labeled `good-first-issue`. For larger features, please open an issue first to discuss the design.

> Engineering principles such as "self-implement the ReAct loop" and "disable Spring AI auto tool execution" are enforced in [CLAUDE.md](CLAUDE.md) — please keep them in mind when touching the core modules.

## Community

OryxOS is a flagship project of [**oryx-labs**](docs/oryx-labs.md) — an AI-coding-driven community of builders exploring AI infra, agents, AI applications, and AI tools, purely driven by curiosity.

## License

[Apache License 2.0](LICENSE) © oryx-labs
