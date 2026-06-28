# Architecture

OryxOS is a Spring Boot 3.x single-binary application running on JDK 21. The entire system — LLM routing, reasoning loop, memory, tools, REST API — ships as one fat JAR. No external dependencies are required beyond a JVM and the LLM provider credentials you configure. State is stored in SQLite and on the local filesystem under `.oryxos/`.

The reasoning engine is a self-implemented ReAct loop. Spring AI Alibaba handles LLM protocol translation; OryxOS owns the loop, the context assembly, the tool dispatch, and the audit records.

## Architecture Diagram

![OryxOS Architecture](/images/architecture.png)

## Layer Diagram

![OryxOS Layer Diagram](/images/layer-diagram.svg)

**Channel Layer** — the two entry points. CLI handles interactive use and local debugging. REST API handles business system integration. Both funnel into the same engine.

**Engine Layer** — the Agent brain. `ReActLoop` drives each iteration: assemble prompt, call LLM, inspect response, execute tools, append results, repeat. `PromptBuilder` assembles the four-part prompt on each iteration. `ToolExecutor` dispatches tool calls, enforces sandbox policy, and writes audit records.

**Capability Layer** — what the engine calls out to. `ProviderService` routes LLM calls to the configured provider. `MemoryService` provides conversation history and long-term memory. `ToolRegistry` holds all registered tools, built-in and MCP-backed alike.

**Storage Layer** — persistence. SQLite stores sessions, tool invocation records, and LLM call records. The filesystem stores Profile YAMLs, Bootstrap files, `MEMORY.md`, and Skill files — anything a user might want to edit directly or track in git.

## Module Structure

| Module | Responsibility |
| --- | --- |
| `oryxos-core` | Core abstractions and interfaces: `OryxTool`, `Session`, `Profile`, `ContextLoader`, `ReActLoop`, `PromptBuilder`, `ToolExecutor`, `AgentService` |
| `oryxos-provider` | LLM routing: `ProviderService`, Function Calling format adaptation, explicit `provider name → ChatModel` mapping |
| `oryxos-memory` | Memory: `MemoryService` facade, `LongTermMemory` (reads/writes `MEMORY.md`), `MemoryTools` (`save_memory`, `recall_memory`) |
| `oryxos-tool` | Tool system: built-in tools (`FileTools`, `ShellTools`, `HttpTools`), `McpClientService`, `McpToolAdapter`, `ToolRegistry`, `SandboxChecker` |
| `oryxos-channel-cli` | CLI channel: `CliChannel`, `oryxos chat` command implementation |
| `oryxos-web` | REST API: `WebServer`, six `ApiController` classes, `GlobalExceptionHandler`, OpenAPI spec |
| `oryxos-storage` | Persistence: SQLite via Spring Data JPA, `SessionRepository`, `ToolInvocationRepository`, `LlmCallRepository` |
| `oryxos-cli` | CLI entry point: Picocli main, 12 subcommands, `ConfigLoader` for credentials and config validation |
| `oryxos-boot` | Spring Boot bootstrap: main class, auto-configuration, dependency aggregation |

Modules communicate through interfaces. Adding a new Channel or Tool implementation means adding a new module — `oryxos-core` is not touched.

## Key Technical Decisions

| # | Decision | Choice | Reason |
| --- | --- | --- | --- |
| 1 | ReAct loop implementation | Self-implemented; does not use Spring AI's Agent abstractions | Full control over loop behavior; no hidden tool double-execution |
| 2 | Spring AI usage boundary | Protocol translation and `@Tool` JSON Schema generation only; automatic tool execution explicitly disabled | Spring AI's auto-execution would run tools twice — once by Spring AI, once by `ToolExecutor` |
| 3 | Execution model | Synchronous blocking with Java 21 virtual threads | Straightforward code, high concurrency without reactive programming complexity |
| 4 | Tool registration | `@Tool` annotation for schema generation + `OryxTool` abstraction layer | Uniform interface for built-in, `@Tool`-annotated, and MCP-backed tools; `ReActLoop` does not care where a tool comes from |
| 5 | HTTP service layer | Spring MVC + Java 21 virtual threads | Sync-style code, thousands of concurrent requests per node; `SseEmitter` available for streaming in extension phase |
| 6 | Sandbox strategy | Path/pattern allowlists at the application layer | `SecurityManager` is deprecated since JDK 17 and unavailable on JDK 21; full container-level sandbox is extension-phase work |
| 7 | Persistence | SQLite + Spring Data JPA for relational data; `MEMORY.md` file for long-term memory | Single binary, no external database process required; audit tables written from day one so auditability is never retrofitted |

## Tech Stack

| Component | Choice |
| --- | --- |
| Language / runtime | Java 21 (required; virtual threads, no SecurityManager) |
| Framework | Spring Boot 3.x |
| LLM integration | Spring AI + Spring AI Alibaba (protocol translation and `@Tool` schema generation only) |
| HTTP service | Spring MVC with Java 21 virtual threads |
| CLI | Picocli |
| YAML parsing | SnakeYAML |
| Persistence | SQLite + Spring Data JPA (`hibernate.ddl-auto=update` for initial setup; manual migration scripts for schema evolution) |
| Long-term memory | `MEMORY.md` flat file, keyword search; vector retrieval planned for extension phase |
| MCP integration | MCP Java SDK (MCP Client for connecting to external MCP servers) |
| Logging | Logback + SLF4J, structured JSON output |
| Build | Maven multi-module |
| Future: native binary | GraalVM Native Image (extension phase, reduces startup from ~3s to <100ms) |
| Future: observability | Micrometer + Prometheus (extension phase) |
