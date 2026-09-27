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
| `shell` | `ShellTools` | Execute a shell command | Command whitelist + direct argv (no shell interpretation) + timeout |
| `http_get` / `http_post` | `HttpTools` | HTTP GET / POST | GET: default allow + SSRF blocklist; POST: domain wildcard whitelist |
| `http_request` | `HttpTools` | HTTP with any method (GET/POST/PUT/PATCH/DELETE) + headers | GET: default allow + SSRF; write methods: domain wildcard whitelist |
| `fetch_webpage` | `HttpTools` | Fetch a URL and extract readable text (strip HTML) | Default allow + SSRF blocklist |
| `download_file` | `HttpTools` | Download a URL to a local file | URL: default allow + SSRF; local path: path whitelist |
| `web_search` | `WebSearchTools` | Search the web | Default allow + SSRF blocklist |
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
| `slack` | Slack incoming webhook or bot token + channel_id |
| `discord` | Discord incoming webhook or bot token + channel_id |
| `telegram` | Telegram Bot API sendMessage |
| `whatsapp` | WhatsApp Cloud API (24h window / templates) |
| `teams` | Microsoft Teams incoming webhook |
| `gchat` | Google Chat incoming webhook |
| `mattermost` | Mattermost incoming webhook |
| `matrix` | Matrix Client-Server send |
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
    transport: stdio
    command: npx -y @modelcontextprotocol/server-github
    request_timeout: 120
    env:
      GITHUB_TOKEN: ${GITHUB_TOKEN}

  - name: my-internal-api
    transport: http
    url: https://mcp.internal.example.com/sse
    request_timeout: 300
    headers:
      Authorization: Bearer ${INTERNAL_API_TOKEN}

  - name: github-hosted
    transport: streamable
    url: https://api.githubcopilot.com/mcp/
    request_timeout: 120
    headers:
      Authorization: Bearer ${GITHUB_TOKEN}
```

OryxOS connects each MCP server at startup: `stdio` launches a local subprocess; `http` / `sse` connect to a remote SSE server; `streamable` connects via Streamable HTTP. Tools exposed by the server are registered in `ToolRegistry` under their declared names.

For remote transports, `url` may contain a complete endpoint path and query, such as `https://mcp.example.com/team/mcp?tenant=example`; both are preserved on requests. A root-only URL (with no path or just `/`) keeps the default endpoint: `/sse` for `http`/`sse`, or `/mcp` for `streamable`/`auto`. The admin console supports all five transport choices and preserves remote URLs, headers, and request timeouts when creating or editing a server.

Use `transport: auto` to try Streamable HTTP first during initialization. Only an initial HTTP `400`, `404`, or `405` response without a JSON-RPC error permits a legacy SSE attempt. The failed client is closed before fallback, which uses the **same endpoint URL**, query, and headers: OryxOS does not search for another `/sse` path. Configure the actual MCP endpoint, such as `/team/mcp` or `/team/events`. Explicit `http`/`sse`/`streamable` configurations do not auto-switch.

Authentication failures, rate limiting, network/TLS errors, timeouts, other HTTP failures, and JSON-RPC errors do not trigger fallback. Error-body inspection is bounded; an oversized or incomplete response fails without guessing the transport. Once initialized, automatic transport selection does not switch transports or add retries for `tools/list` or `tools/call`. Existing SDK session/reconnection behavior remains unchanged; this option is not an exactly-once execution guarantee. `auto` does not implement Tasks or the newer stateless protocol, and keeps the existing per-client initialization and request timeouts.

> **Config schema.** `McpConfigLoader` parses a top-level `servers:` list. Each entry has `name` and `transport`, plus `command`/`env` for `stdio` or `url`/`headers` for remote HTTP transports (`http`/`sse` = legacy SSE endpoint; `streamable` = Streamable HTTP endpoint). `command` is a single whitespace-split string; there is **no separate `args:` field**. Optional `request_timeout` is an integer number of seconds from 1 to 3600 and defaults to 30; a non-integer or out-of-range value is a configuration error that prevents startup. It controls regular requests such as `tools/call`, but does not change the SDK's separate 20-second initialization timeout. The startup/admin `tools/list` connection probe waits at most `min(request_timeout, 60)` seconds so an unhealthy server cannot block the control plane for an hour. `${ENV_VAR}` placeholders are resolved only in `env` and `headers` values, so secrets must not be embedded in `command` or `url`.

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

Each entry carries comments on prerequisites (Node.js/`npx` or `uv`/`uvx` on the host), credential handling, and whether the vendor now ships an official replacement. Use `transport: streamable` for remote servers that speak Streamable HTTP (for example GitHub's hosted MCP and Sentry's remote MCP); keep `http`/`sse` for legacy SSE endpoints. Where an exact package name may have changed, the catalog says so in a comment rather than guessing.

## Sandbox

`SandboxChecker` runs before every tool invocation. It enforces three independent whitelists configured as top-level keys in `application.yml`. An empty list means **deny-all** — the sandbox is closed by default and you widen it explicitly, following least privilege. The configured workspace root is added to the file whitelist automatically at runtime.

**File path whitelist** — applies to `read_file`, `write_file`, `list_dir`. The requested path's real target must remain under at least one entry; symlinks cannot escape an allowed root.

```yaml
file:
  allowed_paths:
    - .oryxos
    - /tmp/oryxos
```

**Shell executable whitelist** — applies to `shell`. Its input is structured: an allowlisted `executable` and an `arguments` array. `ShellTools` passes the argv directly to `ProcessBuilder`; it does not invoke a shell or interpret pipes, redirects, substitutions, or command separators.

```json
{
  "executable": "grep",
  "arguments": ["-R", "TODO", "src"]
}
```

Adding a shell interpreter or language runtime is an explicit administrator grant of code-execution authority: the model can run code with the OS identity of the OryxOS process. Direct argv execution prevents shell-syntax injection, but it does not isolate the interpreter's file or network effects. Use the container-backed `execute_code` tool (`oryxos.tool.execute-code.enabled=true` and `oryxos.sandbox.execution.backend=docker`, reusing the short-lived docker ProcessStarter; fail-loud with no silent local fallback when docker is unavailable).

### `delegate_agent` (in-process handoff)

Hand a subtask to another Agent in an isolated stateless session (`processStateless`). This is not the A2A network protocol. Self-delegation is rejected; nest depth defaults to 3 (`oryxos.tool.delegate-agent.max-depth`). Disable with `oryxos.tool.delegate-agent.enabled=false` (enabled by default).

```yaml
shell:
  allowed_commands:
    - ls
    - cat
    - echo
    - grep
    - python3 # trusted-local code execution; not an isolation boundary
  timeout_seconds: 30
```

The shell whitelist is an independent capability boundary. Adding an interpreter or shell such as `python`, `python3`, `sh`, or `bash` grants the agent the capabilities of that executable and can bypass the file and HTTP tool policies. They are deliberately excluded from the default configuration. Add such entries only for trusted scripts and only when that broader authority is intentional.

**HTTP sandbox (read/write split)** — since section 32, read and write requests use different policies:

- **Read** (`http_get`, GET via `http_request`, `fetch_webpage`, `web_search`, download URLs): **allow by default**, with an SSRF blocklist for private/loopback/cloud-metadata targets. An empty `http.allowed_domains` does **not** block public GETs.
- **Write** (`http_post`, non-GET `http_request`): `http.allowed_domains` domain wildcard whitelist. An empty list means deny-all for writes. Supports `*` as a prefix wildcard.

```yaml
http:
  allowed_domains:
    - "*.feishu.cn"
    - "api.deepseek.com"
    - "api.open-meteo.com"
```

The three whitelists are also **manageable at runtime** via the `/api/v1/sandbox/whitelist` API and the admin console — add or remove entries under the `FILE`, `SHELL`, or `HTTP` categories without a restart.

### Enabling web search for IM / business agents

`web_search`, `http_get`, and `fetch_webpage` are registered globally at runtime, but **only tools listed in that agent’s `AGENT.md` `tools:` frontmatter are exposed to the model** (see “Tool registry” filtering below). A common pitfall: the channel is `CONNECTED`, the user asks to “search the web”, and no tool is ever called — usually because the profile only lists `read_file` / `shell` / `notify`.

The Admin / API **create-agent scaffold** now includes the tools below by default; existing agents still need a manual update:

```yaml
tools:
  - read_file
  - shell
  - notify
  - web_search
  - http_get
  - fetch_webpage
```

In the agent body, require calling `web_search` first for live facts. The core-stage provider is DuckDuckGo: **when Instant Answer JSON is empty (common for Chinese / time-sensitive queries), it automatically retries the HTML lite results page**. If still empty, fall back to `fetch_webpage` or `http_get` against a public API (e.g. weather via `api.open-meteo.com`).

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
