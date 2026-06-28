<p align="center">
  <img src="docs/images/logo.svg" alt="OryxOS" width="256"/>
</p>

<p align="center">
  <strong>Enterprise Agent OS — run multiple AI agents on your own infrastructure</strong>
</p>

<p align="center">
  <a href="https://github.com/oryx-labs/oryxos/releases"><img src="https://img.shields.io/badge/version-1.0.0--SNAPSHOT-orange?style=flat-square" alt="version"/></a>
  <a href="https://www.java.com"><img src="https://img.shields.io/badge/Java-21-007396?style=flat-square&logo=openjdk&logoColor=white" alt="Java 21"/></a>
  <a href="https://spring.io/projects/spring-boot"><img src="https://img.shields.io/badge/Spring%20Boot-3.x-6DB33F?style=flat-square&logo=springboot&logoColor=white" alt="Spring Boot 3"/></a>
  <a href="https://www.apache.org/licenses/LICENSE-2.0"><img src="https://img.shields.io/badge/license-Apache%202.0-blue?style=flat-square" alt="Apache 2.0"/></a>
</p>

---

OryxOS is a self-hosted Enterprise Agent OS built on **Java 21 + Spring Boot 3**. Deploy it on your own Kubernetes cluster or servers as a unified platform for multiple business AI agents — sharing channels, LLM routing, tool execution, memory systems, and sandboxed execution.

No vendor lock-in. No data leaving your environment.

## Features

**🔀 Zero-code LLM Provider Switching**
Switch between DeepSeek, Qwen, Kimi, OpenAI, or any local Ollama model by editing one line in a YAML Profile — no code changes, no redeployment. Each agent can independently use a different provider and model.

**🔄 Self-implemented ReAct Loop**
OryxOS owns the full Reason → Act → Observe cycle. It does not delegate to Spring AI's agent abstractions — `ToolExecutor` dispatches every tool call, enforces sandbox whitelists, writes audit records, and feeds results back into the loop. You get complete visibility and control.

**🧠 Three-layer Persistent Memory**
Agents remember across sessions. Session memory keeps the current conversation; long-term memory (`MEMORY.md`) is automatically injected into every system prompt so preferences and facts persist indefinitely; episodic memory is on the roadmap. `save_memory` and `recall_memory` tools let agents write and search their own memory.

**📋 Built-in Audit Trail**
Every tool call and every LLM request is written to `tool_invocations` and `llm_calls` tables in SQLite from day one — not as an afterthought. Production incidents are fully traceable without log parsing.

**🔌 Extensible Tool & MCP Ecosystem**
Seven built-in tools (file read/write, shell, HTTP, memory) cover common needs out of the box. For everything else: point to any community MCP server in `mcp_servers.yaml` with zero Java code, or register a Spring `@Tool` bean for in-process execution. New channels and tools are added as independent Maven modules — `oryxos-core` stays untouched.

## Architecture

<p align="center">
  <img src="docs/images/architecture.svg" alt="OryxOS Architecture" width="100%"/>
</p>

## Why OryxOS

| Pain point | OryxOS answer |
| --- | --- |
| Hardcoded LLM endpoints — code changes to switch models | Profile YAML: one line to switch provider, zero code |
| Agents forget everything between sessions | Three-layer memory; long-term MEMORY.md auto-injected into every prompt |
| No audit trail — production incidents untraceable | `tool_invocations` + `llm_calls` tables written from day one |
| Every team rebuilds the same agent infrastructure | Unified platform — multiple agents share channels and tools |

## Module Structure

```text
oryxos/
├── oryxos-core          # OryxTool interface, Session, ReActLoop, PromptBuilder, ToolExecutor
├── oryxos-provider      # ProviderService, Function Calling adapter, explicit multi-provider map
├── oryxos-memory        # MemoryService, LongTermMemory, MemoryTools (save/recall)
├── oryxos-tool          # Built-in tools (file/shell/http), MCP Client, ToolRegistry, SandboxChecker
├── oryxos-channel-cli   # CLI channel: oryxos chat implementation
├── oryxos-web           # 10 REST endpoints, ApiController, GlobalExceptionHandler
├── oryxos-storage       # SQLite, SessionRepository, ToolInvocationRepository, LlmCallRepository
├── oryxos-cli           # Picocli entry, 12 subcommands, ConfigLoader
└── oryxos-boot          # Spring Boot main class, auto-configuration, dependency aggregation
```

Modules are decoupled through interfaces. Adding a new Channel or Tool only requires a new module — `oryxos-core` stays untouched.

## Quick Start

**Prerequisites**: Java 21, Maven 3.9+, an LLM API key (DeepSeek / Qwen / OpenAI / Ollama)

```bash
# Build
git clone https://github.com/oryx-labs/oryxos.git
cd oryxos
mvn package -DskipTests

# Initialize the workspace
java -jar oryxos-boot/target/oryxos-boot-*.jar init

# Set your LLM API key
export DEEPSEEK_API_KEY=your-key-here

# Start chatting
java -jar oryxos-boot/target/oryxos-boot-*.jar chat --profile default

# Or launch the REST API
java -jar oryxos-boot/target/oryxos-boot-*.jar serve --port 8080
```

## CLI Reference

```bash
oryxos init                      # Initialize .oryxos/ workspace
oryxos status                    # Show configuration and runtime status
oryxos chat [--profile <name>]   # Interactive multi-turn chat
oryxos serve [--port 8080]       # Launch HTTP API server
oryxos gateway                   # Daemon mode (multi-channel)

oryxos profile list
oryxos profile create <name>
oryxos profile show <name>
oryxos profile delete <name>

oryxos provider list
oryxos tool list
oryxos session list
```

## Agent Profile

Each agent is defined by a single YAML file under `.oryxos/profiles/`:

```yaml
name: ops-agent
description: DevOps assistant
identity:
  agent_name: ops-agent
  prompt: You are a professional DevOps assistant...
provider:
  name: deepseek          # Switch to qwen / ollama / openai — zero code change
  model: deepseek-chat
  api_key: ${DEEPSEEK_API_KEY}
tools:
  - shell
  - read_file
  - http_get
  - save_memory
  - recall_memory
settings:
  max_iterations: 10
  max_history_turns: 20
```

## REST API

All endpoints are prefixed with `/api/v1`:

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/sessions` | Create a session |
| `POST` | `/sessions/{id}/messages` | Send a message (triggers ReAct Loop) |
| `GET` | `/sessions/{id}` | Get session history |
| `DELETE` | `/sessions/{id}` | Archive a session |
| `POST` | `/agents/{name}/invoke` | Stateless agent invocation |
| `GET` | `/profiles` | List all profiles |
| `GET` | `/memory` | Read long-term memory |
| `GET` | `/tools` | List available tools |
| `GET` | `/health` | Health check |
| `GET` | `/info` | Runtime info + provider status |

## ReAct Loop

```text
User message
  → PromptBuilder: system prompt (identity + bootstrap + SKILL.md)
                 + long-term memory (MEMORY.md)
                 + conversation history (max_history_turns)
                 + available tools (function calling format)
  → ProviderService: ChatModel.call()
  → [No tool call]  → return final response
  → [Tool call]     → SandboxChecker whitelist validation
                    → ToolExecutor (built-in in-process / MCP via JSON-RPC)
                    → write tool_invocations audit table
                    → append result → loop (max_iterations)
```

## Built-in Tools

| Tool | Description |
| --- | --- |
| `read_file` | Read files; path whitelist enforced |
| `write_file` | Write files; path whitelist enforced |
| `list_dir` | List directories; path whitelist enforced |
| `shell` | Execute shell commands; command whitelist + timeout |
| `http_get` | HTTP GET; domain whitelist enforced |
| `http_post` | HTTP POST; domain whitelist enforced |
| `save_memory` | Append to MEMORY.md (long-term memory) |
| `recall_memory` | Keyword search in MEMORY.md |

## Supported LLM Providers

| Provider | Example models |
| --- | --- |
| `deepseek` | deepseek-chat, deepseek-coder |
| `qwen` | qwen-max, qwen-plus |
| `kimi` | moonshot-v1-8k, moonshot-v1-32k |
| `openai` | gpt-4o, gpt-4o-mini |
| `ollama` | qwen2.5:7b, llama3, any local model |

## Tech Stack

| Component | Choice |
| --- | --- |
| Language / Runtime | Java 21 (virtual threads) |
| Framework | Spring Boot 3.x |
| LLM Integration | Spring AI Alibaba (protocol translation + `@Tool` schema only) |
| CLI | Picocli |
| Config | SnakeYAML |
| Persistence | SQLite + Spring Data JPA |
| Logging | Logback + SLF4J (structured JSON) |
| Build | Maven multi-module |

## License

[Apache License 2.0](LICENSE)
