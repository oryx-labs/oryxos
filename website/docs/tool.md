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

About two dozen tools ship with OryxOS core — a set of **universal primitives** chosen to cover the vast majority of agent needs without domain lock-in. All are registered automatically at startup. Which ones an agent can use depends on its Profile's `tools` list.

| Tool | Class | Description | Sandbox check |
| --- | --- | --- | --- |
| `read_file` | `FileTools` | Read a file from disk | Path whitelist |
| `write_file` | `FileTools` | Write or overwrite a file | Path whitelist |
| `edit_file` | `FileTools` | Replace a unique snippet in a file | Path whitelist |
| `append_file` | `FileTools` | Append content to a file | Path whitelist |
| `list_dir` | `FileTools` | List directory contents | Path whitelist |
| `glob` | `FileTools` | Find files by glob pattern | Path whitelist |
| `grep` | `FileTools` | Regex-search file contents | Path whitelist |
| `make_dir` | `FileTools` | Create a directory | Path whitelist |
| `move_file` | `FileTools` | Move / rename a file | Path whitelist (source + target) |
| `copy_file` | `FileTools` | Copy a file | Path whitelist (source + target) |
| `delete_file` | `FileTools` | Delete a file (never a directory) | Path whitelist |
| `shell` | `ShellTools` | Execute a shell command | Command first-token whitelist + timeout |
| `http_get` / `http_post` | `HttpTools` | HTTP GET / POST | Domain wildcard whitelist |
| `http_request` | `HttpTools` | HTTP with any method (GET/POST/PUT/PATCH/DELETE) + headers | Domain wildcard whitelist |
| `fetch_webpage` | `HttpTools` | Fetch a URL and extract readable text (strip HTML) | Domain wildcard whitelist |
| `download_file` | `HttpTools` | Download a URL to a local file | Domain + path whitelist |
| `web_search` | `WebSearchTools` | Search the web | Domain wildcard whitelist |
| `current_time` | `UtilTools` | Current date/time in a timezone | None (pure) |
| `json_extract` | `UtilTools` | Extract a value from JSON text by path | None (pure) |
| `save_memory` | `MemoryTools` | Append text to `MEMORY.md` | None (always allowed) |
| `recall_memory` | `MemoryTools` | Keyword search in `MEMORY.md` | None (always allowed) |
| `notify` | `NotifyTools` | Push a message to a registered notify channel | Resolves channel by name |
| `ask_user` | `InteractionTools` | Ask the user a question (interactive channels) | None |

`ShellTools` enforces a configurable timeout (default 30 seconds) in addition to the command whitelist. A process that exceeds the timeout is killed and the tool returns a non-retryable error.

## The `notify` tool and notify channels

`notify` pushes a message to a **notify channel referenced by name**. The tool takes a `channel` argument (the channel's registered name) plus the `content` to send; it resolves that name to a registered channel, picks the adapter for the channel's type, and delivers to the channel's URL.

Notify channels are managed as first-class resources — created, edited, and deleted via the **`/api/v1/notify-channels`** API or the web admin console (the "Notify 渠道" page), and stored in the SQLite `notify_channels` table. Each channel has a `name`, a `type`, a `url`, and an optional `description`. Supported types:

| Type | Delivers to |
| --- | --- |
| `feishu` | Feishu / Lark group webhook |
| `wecom` | WeCom (企业微信) group webhook |
| `dingtalk` | DingTalk group webhook |
| `webhook` | Generic HTTP webhook |

An agent references a channel **by name from its `AGENT.md` body**, in plain language — for example, "call notify and send the report to `team-lark`". There is **no `notify_channels` field in AGENT.md frontmatter**; the channel is resolved at call time from the registry, so channels can be added or re-pointed without touching any agent.

## Three-tier extension

Adding capabilities beyond the built-in tools follows one of three patterns depending on how much code you want to write:

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

> **Config schema.** `McpConfigLoader` parses a top-level `servers:` list where each entry has four fields: `name`, `transport` (only `stdio` in the core phase — `http`/`sse` entries are skipped at startup with a WARN), `command` (a single string, split on whitespace into executable + args — there is no separate `args:` field), and `env` (a map). `${ENV_VAR}` placeholders are resolved **only inside `env:` values**, not inside `command:` — so secrets belong in `env:`, never inline in `command:`.

## Recommended MCP servers

OryxOS keeps its built-in tools small on purpose. Built-in tools are **universal primitives** — file, shell, HTTP, memory, notify. Everything domain-specific reaches the agent through **MCP servers**: GitHub, GitLab, Slack, Google Drive, Notion, browser automation, web search, error tracking — and, deliberately, **SQL databases (PostgreSQL, SQLite, …) too**. OryxOS ships no built-in SQL tool; a database is a domain integration, so you connect its MCP server rather than baking a query tool into the core. This is the intended zero/low-code extension path: to add a capability you name a package, not write Java.

To make this concrete, OryxOS ships a curated, ready-to-copy catalog at **`config/mcp_servers.yaml.example`**. Copy it to `.oryxos/mcp_servers.yaml`, uncomment only the servers you want (everything is commented out by default so a fresh copy connects to nothing), set the environment variables each server needs, and restart. The catalog matches the loader's exact schema and lists real, well-known community servers across these categories:

| Category | Servers in the catalog |
| --- | --- |
| Code hosting | GitHub, GitLab |
| Databases (SQL) | PostgreSQL, SQLite |
| Messaging | Slack |
| Documents / storage | Google Drive, Notion |
| Filesystem | Filesystem (richer ops than the built-ins) |
| Browser automation | Playwright (recommended), Puppeteer |
| Web search | Brave Search |
| Observability | Sentry |

Each entry carries comments on prerequisites (Node.js/`npx` or `uv`/`uvx` on the host), credential handling, and — where relevant — whether the vendor now ships an official replacement or a remote (HTTP) server that the core phase's stdio-only transport cannot use yet. Where an exact package name may have changed, the catalog says so in a comment rather than guessing.

## Sandbox

`SandboxChecker` runs before every tool invocation. It enforces three independent whitelists configured as top-level keys in `application.yml`. An empty list means **deny-all** — the sandbox is closed by default and you widen it explicitly, following least privilege. The configured workspace root is added to the file whitelist automatically at runtime.

**File path whitelist** — applies to `read_file`, `write_file`, `list_dir`. The requested path must match at least one entry.

```yaml
file:
  allowed_paths:
    - .oryxos
    - /tmp/oryxos
```

**Shell command whitelist** — applies to `shell`. Only the first token of the command is checked (the executable name). Arguments are not restricted by the whitelist.

```yaml
shell:
  allowed_commands:
    - ls
    - cat
    - grep
    - python3
  timeout_seconds: 30
```

**HTTP domain whitelist** — applies to `http_get` and `http_post`. Supports `*` as a prefix wildcard.

```yaml
http:
  allowed_domains:
    - "*.feishu.cn"
    - "api.deepseek.com"
    - "api.open-meteo.com"
```

The three whitelists are also **manageable at runtime** via the `/api/v1/sandbox/whitelist` API and the admin console — add or remove entries under the `FILE`, `SHELL`, or `HTTP` categories without a restart.

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
