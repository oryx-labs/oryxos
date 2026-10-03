# Tool System

Every capability an agent can invoke is an `OryxTool`. The interface is the same whether the tool is a built-in file reader, an HTTP client, a memory operation, or a remote MCP server. `ToolExecutor` and `ToolRegistry` know nothing about what a tool does — they work through the interface.

## OryxTool interface

```java
interface OryxTool {
    String getName();
    String getDescription();
    JsonSchema getInputSchema();
    ToolResult execute(JsonNode input);
}
```

`getInputSchema()` returns a JSON Schema object that `ProviderService` serializes into the Function Calling format understood by the LLM. The schema tells the model what arguments the tool accepts and what they mean.

`execute(JsonNode input)` receives the arguments the model provided, runs the tool, and returns a `ToolResult`:

```java
record ToolResult(
    boolean success,
    String  content,       // the output shown to the model
    String  errorMessage,  // populated when success = false
    boolean retryable      // true = append error and let model try again
) {}
```

When `retryable` is `true`, `ToolExecutor` appends the error to the conversation history and lets the loop continue. When `retryable` is `false`, the loop surfaces the error to the caller immediately.

## Built-in tools

Seven tools ship with OryxOS core. All are registered automatically at startup. Which ones an agent can use depends on its Profile's `tools` list.

| Tool | Class | Description | Sandbox check |
| --- | --- | --- | --- |
| `read_file` | `FileTools` | Read a file from disk | Path whitelist |
| `write_file` | `FileTools` | Write or overwrite a file | Path whitelist |
| `list_dir` | `FileTools` | List directory contents | Path whitelist |
| `shell` | `ShellTools` | Execute a shell command | Command first-token whitelist + timeout |
| `http_get` | `HttpTools` | HTTP GET request | Domain wildcard whitelist |
| `http_post` | `HttpTools` | HTTP POST request with body | Domain wildcard whitelist |
| `save_memory` | `MemoryTools` | Append text to `MEMORY.md` | None (always allowed) |
| `recall_memory` | `MemoryTools` | Keyword search in `MEMORY.md` | None (always allowed) |

`ShellTools` enforces a configurable timeout (default 30 seconds) in addition to the command whitelist. A process that exceeds the timeout is killed and the tool returns a non-retryable error.

## Three-tier extension

Adding capabilities beyond the built-in seven follows one of three patterns depending on how much code you want to write:

| | Zero-code | Light-code | Heavy-code |
| --- | --- | --- | --- |
| **Approach** | Write a `SKILL.md` + wire up an existing community MCP server | Write an MCP server in any language, add it to `mcp_servers.yaml` | Implement `OryxTool` as a Spring `@Bean` in a Java module |
| **Where it runs** | MCP server runs as a subprocess or remote process | Same as zero-code, but you wrote the server | In-process with OryxOS JVM |
| **Use case** | Standard integrations: GitHub, Slack, databases, web search | Domain-specific logic, internal systems, proprietary APIs | Performance-critical tools, tools needing direct access to OryxOS internals |
| **Recommended for** | Most production use cases | Moderate integration complexity | Power users with Java expertise |

Zero-code is the primary recommendation. The community MCP ecosystem covers most common integrations. A `SKILL.md` provides the agent with instructions on how to use the server's tools — the MCP server provides the tools themselves.

MCP server configuration in `mcp_servers.yaml`:

```yaml
servers:
  - name: github-mcp
    command: npx
    args: ["-y", "@modelcontextprotocol/server-github"]
    env:
      GITHUB_TOKEN: ${GITHUB_TOKEN}

  - name: my-internal-api
    command: python3
    args: ["/opt/tools/my_mcp_server.py"]
    env:
      API_BASE: ${INTERNAL_API_BASE}
```

OryxOS starts each MCP server as a subprocess at startup and communicates over JSON-RPC via stdio. Tools exposed by the server are registered in `ToolRegistry` under their declared names.

## Sandbox

`SandboxChecker` runs before every tool invocation. It enforces three independent whitelists configured in `oryxos.yaml`.

**File path whitelist** — applies to `read_file`, `write_file`, `list_dir`. The requested path must match at least one entry.

```yaml
sandbox:
  file:
    allowed_paths:
      - /home/user/workspace
      - /tmp/oryxos
```

**Shell command whitelist** — applies to `shell`. Only the first token of the command is checked (the executable name). Arguments are not restricted by the whitelist.

```yaml
sandbox:
  shell:
    allowed_commands:
      - git
      - grep
      - cat
      - curl
    timeout_seconds: 30
```

**HTTP domain whitelist** — applies to `http_get` and `http_post`. Supports `*` as a prefix wildcard.

```yaml
sandbox:
  http:
    allowed_domains:
      - "*.github.com"
      - "api.openai.com"
      - "*.internal.company.com"
```

If a tool call fails the sandbox check, `ToolExecutor` returns a non-retryable `ToolResult` with a clear error message describing which whitelist was violated. The call is still recorded in `tool_invocations` with `success = false`.

`SecurityManager` is not used. It was deprecated in JDK 17 and removed in JDK 21. The sandbox is purely application-level: whitelists are enforced in `SandboxChecker` before the tool code runs.

## Tool registry

`ToolRegistry` is the central catalog of all available tools — built-in and MCP. It has two responsibilities:

**Registration** — at startup, all `OryxTool` beans are registered, then MCP server tools are discovered via JSON-RPC `tools/list` and wrapped as `McpProxyTool` instances.

**Filtering** — when `PromptBuilder` asks for the tools an agent can use, `ToolRegistry.getToolsForProfile(profile)` returns only the tools listed in `profile.tools`. An agent cannot call a tool that is not in its Profile, even if the tool is registered globally.

```java
// PromptBuilder uses this to build the Function Calling tool list for the LLM
List<OryxTool> tools = toolRegistry.getToolsForProfile(profile);
```

Tool names are globally unique. If an MCP server registers a tool with the same name as a built-in tool, startup fails with a clear error — there is no silent shadowing.
