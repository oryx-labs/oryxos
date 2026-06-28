# What is OryxOS

***A Java-native Agent OS that runs and manages a fleet of business agents on your own infrastructure — shared channels, LLM routing, memory, tools, and auditable execution, all in one deployable binary.***

## What it is

OryxOS is a Spring Boot 3.x application that runs on JDK 21 as a unified Agent platform for enterprise deployments. You write a YAML Profile to define an Agent — its identity, which LLM it talks to, which tools it can use, which memory it shares. OryxOS handles everything else: the reasoning loop, context assembly, tool execution, sandbox enforcement, session persistence, and REST API exposure. Multiple Agents run inside a single instance simultaneously. Business systems integrate via HTTP. Data stays on your own infrastructure.

## Agent OS vs Agent Runtime

These terms are frequently conflated. They describe fundamentally different scopes.

| | Agent Runtime | Agent OS |
| --- | --- | --- |
| Scope | Single agent | Fleet of agents |
| Manages | Reasoning loop, context, tool execution | Lifecycle, channels, memory, governance |
| Entry point | Library or framework call | Deployable binary with REST API |
| Multi-agent | Not in scope | First-class: multiple Profiles, shared capabilities |
| Analogy | Process execution environment | The OS layer above processes |

A runtime gets one agent running. An Agent OS gets a fleet of agents running and managed.

OryxOS contains a runtime (the self-implemented ReAct loop) but is designed as the OS layer above it: unified channel ingestion, shared memory, centralized tool registry, auditable invocation records, and REST API exposure that any language can call.

## Five Core Capabilities

### LLM Routing

Provider abstraction over mainstream models: DeepSeek, Qwen, Kimi, Zhipu, Hunyuan, Doubao, Anthropic, OpenAI, and any OpenAI-protocol-compatible endpoint. Agents are provider-agnostic — the Profile declares which provider to use; the agent never knows which vendor is behind the call. Switch providers at config time with no code change. Multiple providers co-exist via explicit name-to-`ChatModel` mapping, not bean scanning. Local inference via Ollama or vLLM is supported.

### ReAct Loop

Self-implemented reasoning engine — no external Agent framework wrapping. Each iteration: assemble prompt (system prompt + bootstrap context + long-term memory + conversation history + available tools), call LLM, inspect response for tool calls, execute tools, append results, repeat. Loop continues until the LLM produces a final response or the configured iteration limit is reached. The entire loop is a few dozen lines of Java and is fully inspectable. Spring AI is used only for LLM protocol translation — its automatic tool execution is explicitly disabled.

### Memory

Two-layer memory in the core phase. Session memory holds the current conversation history, persisted to SQLite and recoverable across restarts. Long-term memory lives in `MEMORY.md` — a Markdown file agents write to via `save_memory` and search via `recall_memory` (keyword matching). The full file is injected into every system prompt so agents have persistent context across conversations. Files over 4,000 characters are truncated to stay within context limits. Vector retrieval is the planned upgrade path for the extension phase.

### Tool System

Built-in tools cover the baseline: `read_file`, `write_file`, `list_dir`, `shell`, `http_get`, `http_post`, `save_memory`, `recall_memory`. All execute with sandbox enforcement — path allowlist for files, command allowlist for shell, domain allowlist for HTTP.

Extension follows three tiers, ordered by effort:

| Tier | Effort | Approach |
| --- | --- | --- |
| Zero-code | Lowest | Write a `SKILL.md` describing the task, reference existing community MCP servers in Profile |
| Light-code | Medium | Write an MCP server in any language; OryxOS connects as MCP Client |
| Heavy-code | Highest | Annotate a Spring Bean with `@Tool`; registers directly in-process |

All tools — built-in, MCP-backed, and native — are registered through `ToolRegistry` and expose a uniform `OryxTool` interface to the ReAct loop.

### REST API

Ten REST endpoints under `/api/v1` expose all capabilities to external systems: session lifecycle management, stateless agent invocation, profile listing, memory inspection, tool inventory, health check, and runtime info. Any language that can send HTTP requests can integrate. No SDK required for the core phase. The Web Service is the integration boundary — business systems plug in here, not at the library level.

## Design Principles

- **Platform before Agent** — the most important deliverable is the environment that lets any agent run reliably, not any particular agent
- **Self-implement the core, reuse the plumbing** — the reasoning loop is written by hand; LLM protocol adapters delegate to Spring AI Alibaba
- **Config = Agent** — an Agent is defined entirely by a YAML Profile, not by code
- **Open standards** — MCP for tools, A2A for agent-to-agent collaboration, `SKILL.md` files for skills
- **Stateless instances, externalized state** — the prerequisite for eventually going distributed without an architectural rewrite
- **Security as foundation, not afterthought** — tool source control, least privilege, mandatory sandbox allowlists, credentials via environment variables, full audit trail written to SQLite from day one
- **Phased and disciplined** — build the minimal complete runtime kernel first; governance and distributed infrastructure come later, proven by real usage data
