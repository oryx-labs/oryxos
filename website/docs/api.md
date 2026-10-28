# REST API

OryxOS exposes a JSON REST API under the `/api/v1` prefix. No authentication is required in the core phase — deployment assumes an internal network. All request and response bodies are JSON.

Start the server with:

```bash
oryxos serve --port 8080
```

---

## Response envelope

**Every** endpoint returns the same envelope. The actual payload lives in `data`:

```json
{
  "code": 0,
  "message": "success",
  "data": { },
  "timestamp": 1720000000000
}
```

| Field | Type | Meaning |
|-------|------|---------|
| `code` | int | `0` on success; non-zero on error |
| `message` | string | `"success"`, or the error description |
| `data` | any | The endpoint payload (object, array, string, or `null`) |
| `timestamp` | long | Server epoch milliseconds |

Errors carry a non-zero `code` and a human-readable `message`, with `data` set to `null`:

```json
{
  "code": 400,
  "message": "创建会话缺少 profile",
  "data": null,
  "timestamp": 1720000000000
}
```

| HTTP status | Trigger | Example |
|-------------|---------|---------|
| `400` | `IllegalArgumentException` — bad or missing input | blank provider name, path traversal, unknown sandbox category |
| `404` | `ResourceNotFoundException` — resource absent | unknown session id, unknown provider name |

In the examples below only the `data` payload is shown for brevity — remember it is always wrapped in the envelope above.

---

## System

### Health check

**GET** `/api/v1/health`

Lightweight liveness probe. Returns `200 OK` when the server is running.

```json
// data
{ "status": "ok" }
```

### Runtime info

**GET** `/api/v1/info`

Returns the application name and the list of configured providers (the "configured" set — the core phase does not live-ping providers).

```json
// data
{
  "application": "oryxos",
  "providers": ["deepseek", "qwen"]
}
```

---

## Agents

An Agent is a directory `.oryxos/agents/<name>/` whose `AGENT.md` holds the frontmatter (the Agent's Profile) plus the task body. These endpoints manage the full dynamic lifecycle — create, read, update, delete, invoke, and inspect an Agent's memory and console session.

### Create an agent

**POST** `/api/v1/agents`

Scaffolds `.oryxos/agents/<name>/AGENT.md`, derives its Profile, and registers it. On failure the partial directory is rolled back.

```json
// request
{
  "name": "ops-agent",
  "description": "Operations assistant"
}
```

```json
// data — AgentView
{
  "name": "ops-agent",
  "description": "Operations assistant",
  "provider": "deepseek",
  "model": "deepseek-chat",
  "tools": ["read_file", "shell", "http_get"],
  "schedules": []
}
```

### List agents

**GET** `/api/v1/agents`

```json
// data — AgentView[]
[
  {
    "name": "ops-agent",
    "description": "Operations assistant",
    "provider": "deepseek",
    "model": "deepseek-chat",
    "tools": ["read_file", "shell", "http_get"],
    "schedules": []
  }
]
```

### Get one agent

**GET** `/api/v1/agents/{name}`

```json
// data — AgentView
{
  "name": "weather-daily",
  "description": "Daily weather + dress advice pushed to Lark",
  "provider": "deepseek",
  "model": "deepseek-chat",
  "tools": ["http_get", "notify"],
  "schedules": [
    { "id": "morning", "cron": "0 0 8 * * *", "zone": "Asia/Shanghai", "message": "推送今天的天气和穿衣建议" }
  ]
}
```

### Update an agent

**PUT** `/api/v1/agents/{name}`

Overwrites the entire `AGENT.md` text. If the `schedules` changed, the Agent is unregistered and re-registered.

```json
// request
{
  "agentMarkdown": "---\nname: ops-agent\ndescription: Operations assistant\n...\n---\n\nYou are an operations assistant. When triggered..."
}
```

```json
// data — AgentView (the re-derived view)
{
  "name": "ops-agent",
  "description": "Operations assistant",
  "provider": "deepseek",
  "model": "deepseek-chat",
  "tools": ["read_file", "shell", "http_get"],
  "schedules": []
}
```

### Delete an agent

**DELETE** `/api/v1/agents/{name}`

Unschedules the Agent, removes it from the registry, and archives its directory (not a physical delete).

```json
// data
null
```

### Stateless invoke

**POST** `/api/v1/agents/{name}/invoke`

Runs a single-turn ReAct Loop without creating a persistent session.

```json
// request
{ "content": "Summarize the last 10 git commits in this repo." }
```

```json
// data — MessageResponse
{ "reply": "The last 10 commits covered ReAct Loop, provider abstraction, and SQLite persistence.",
  "traceId": "3f9c2b1a-…" }
```

`traceId` (021) identifies this single message-processing round end to end, for incident reports and audit replay (see the "Audit trace" section).

### Get an agent's memory

**GET** `/api/v1/agents/{name}/memory`

Returns the raw content of this agent's `MEMORY.md` (`.oryxos/agents/<name>/MEMORY.md`, falling back to the global `.oryxos/memory/MEMORY.md` when there is no agent context). Memory is per-agent; each line is timestamped.

```json
// data — the MEMORY.md text
"2026-06-20 09:12:00 用户偏好使用 Markdown 格式输出结果。\n2026-06-25 14:03:11 线上数据库为 PostgreSQL 15。\n"
```

### Get the console session

**GET** `/api/v1/agents/{name}/session`

Returns this agent's console session (`admin:console:<agent>`) — the session the web manager uses for "立即触发 / chat".

```json
// data — SessionView
{
  "sessionId": "admin:console:ops-agent",
  "profileName": "ops-agent",
  "messages": [
    { "role": "user", "content": "查一下磁盘使用情况", "toolName": null, "toolCallId": null, "toolCalls": [] },
    { "role": "assistant", "content": "当前磁盘使用率 42%。", "toolName": null, "toolCallId": null, "toolCalls": [] }
  ]
}
```

### Send a message to the console session

**POST** `/api/v1/agents/{name}/session/messages`

Sends a message to the console session and runs a full ReAct Loop (this is what the manager's "立即触发 / chat" calls).

```json
// request
{ "content": "现在磁盘使用情况怎么样？" }
```

```json
// data — MessageResponse
{ "reply": "当前磁盘使用率 42%，各挂载点均低于 50%。" }
```

### Generate draft files from one sentence

**POST** `/api/v1/agents/{name}/generate-files`

Sends a one-sentence description to the LLM, which drafts an `AGENT.md`. The result is **preview only** — it is neither saved nor registered.

```json
// request
{ "description": "每天早上把 GitHub trending 推送到飞书" }
```

```json
// data — GeneratedFilesView ({ relativePath -> content })
{
  "files": {
    "AGENT.md": "---\nname: github-daily\ndescription: ...\n---\n\nYou are..."
  }
}
```

### Save edited files

**POST** `/api/v1/agents/{name}/files`

Saves a (possibly user-edited) set of Agent files. Saving takes effect immediately (files written, Profile re-derived, registered).

```json
// request
{
  "files": {
    "AGENT.md": "---\nname: github-daily\n...\n---\n\n...",
    "scripts/github_trending.py": "import urllib.request\n..."
  }
}
```

```json
// data — AgentView
{
  "name": "github-daily",
  "description": "Daily GitHub trending to Lark",
  "provider": "deepseek",
  "model": "deepseek-chat",
  "tools": ["shell", "notify"],
  "schedules": []
}
```

---

## Providers

Providers are managed **dynamically** and stored in SQLite (the `providers` table). At runtime the `ChatModel` is resolved by provider name from the registry and cached by `(name | apiKey | baseUrl)`, so editing a key or URL rebuilds the model on the next call. The name `mock` is a built-in fake model that needs no key or URL. The `oryxos.providers[]` entries in `application.yml` are **seeded** into the table on startup only when absent — after that the DB is authoritative.

> Provider keys are encrypted at rest. Provider API responses return a masked `apiKey`; they never reveal the stored credential.

### Create a provider

**POST** `/api/v1/providers`

`name` must be globally unique; a non-`mock` provider requires a `baseUrl` (an OpenAI-compatible endpoint). A duplicate or blank name returns `400`.

```json
// request — CreateProviderRequest
{
  "name": "deepseek",
  "apiKey": "sk-xxxxxxxx",
  "baseUrl": "https://api.deepseek.com",
  "description": "DeepSeek chat"
}
```

```json
// data — ProviderView
{
  "name": "deepseek",
  "apiKey": "****xxxx",
  "baseUrl": "https://api.deepseek.com",
  "description": "DeepSeek chat"
}
```

### List providers

**GET** `/api/v1/providers`

```json
// data — ProviderView[]
[
  { "name": "deepseek", "apiKey": "****xxxx", "baseUrl": "https://api.deepseek.com", "description": "DeepSeek chat" },
  { "name": "mock",     "apiKey": null,          "baseUrl": null,                       "description": "built-in fake model" }
]
```

### Get one provider

**GET** `/api/v1/providers/{name}`

Returns `404` if the provider does not exist.

```json
// data — ProviderView
{ "name": "deepseek", "apiKey": "****xxxx", "baseUrl": "https://api.deepseek.com", "description": "DeepSeek chat" }
```

### Update a provider

**PUT** `/api/v1/providers/{name}`

The name stays in the path; this updates the key / URL / description.

```json
// request — UpdateProviderRequest
{ "apiKey": "sk-yyyyyyyy", "baseUrl": "https://api.deepseek.com", "description": "rotated key" }
```

```json
// data — ProviderView
{ "name": "deepseek", "apiKey": "****yyyy", "baseUrl": "https://api.deepseek.com", "description": "rotated key" }
```

### Delete a provider

**DELETE** `/api/v1/providers/{name}`

```json
// data
null
```

---

## Notify channels

Notify channels are managed dynamically and stored in SQLite (the `notify_channels` table). Password-like sensitive keys in `config` (`password/secret/token/api_key`, etc.) and the channel `url` (`notify_channels.url` — a webhook URL is itself a credential; the email channel's descriptive URL stays plaintext) are stored encrypted (022, `enc:v1:` prefix); query endpoints echo them only as masks (`****` + last 4 chars), and submitting the mask unchanged (or blank) on edit keeps the original value — same interaction as the provider api-key. The `notify` tool references a channel **by name** in natural language inside an `AGENT.md` body (e.g. "发到 team-lark"); the tool resolves the registered channel to its adapter and URL. There is no `notify_channels` field in the AGENT.md frontmatter.

`type` is one of `feishu` | `wecom` | `dingtalk` | `webhook` (each backed by an adapter).

### Create a notify channel

**POST** `/api/v1/notify-channels`

```json
// request — CreateNotifyChannelRequest
{
  "name": "team-lark",
  "type": "feishu",
  "url": "https://open.larksuite.com/open-apis/bot/v2/hook/xxxx",
  "description": "team Lark group"
}
```

```json
// data — NotifyChannelView
{
  "name": "team-lark",
  "type": "feishu",
  "url": "https://open.larksuite.com/open-apis/bot/v2/hook/xxxx",
  "description": "team Lark group"
}
```

### List notify channels

**GET** `/api/v1/notify-channels`

```json
// data — NotifyChannelView[]
[
  { "name": "team-lark", "type": "feishu", "url": "https://open.larksuite.com/open-apis/bot/v2/hook/xxxx", "description": "team Lark group" }
]
```

### Get one notify channel

**GET** `/api/v1/notify-channels/{name}`

```json
// data — NotifyChannelView
{ "name": "team-lark", "type": "feishu", "url": "https://open.larksuite.com/open-apis/bot/v2/hook/xxxx", "description": "team Lark group" }
```

### Update a notify channel

**PUT** `/api/v1/notify-channels/{name}`

```json
// request — UpdateNotifyChannelRequest
{ "type": "webhook", "url": "https://example.com/hook", "description": "generic webhook" }
```

### Delete a notify channel

**DELETE** `/api/v1/notify-channels/{name}`

```json
// data
null
```

---

## Sessions

A session is a stateful conversation between a user and an Agent. Each session carries message history and is tied to a Profile; history is persisted in SQLite.

### Create a session

**POST** `/api/v1/sessions`

`profile` is required (a blank profile returns `400`); `userId` defaults to `default` when omitted. The channel is fixed to the web channel for API-created sessions.

```json
// request — CreateSessionRequest
{ "profile": "ops-agent", "userId": "user-001" }
```

```json
// data
{ "sessionId": "web:user-001:ops-agent" }
```

### Send a message

**POST** `/api/v1/sessions/{id}/messages`

Appends the user message to the session history and runs the ReAct Loop. Blocks until the agent produces a final response (synchronous). Unknown session id returns `404`; empty content returns `400`.

```json
// request — MessageRequest
{ "content": "查一下服务器当前的磁盘使用情况" }
```

```json
// data — MessageResponse
{ "reply": "当前磁盘使用情况：/dev/sda1 使用率 42%，其余挂载点均低于 30%。" }
```

### SSE streaming

Three message endpoints (this one, [stateless invoke](#stateless-invoke), and [send a message to the console session](#send-a-message-to-the-console-session)) support streaming: send `Accept: text/event-stream` to switch the response to an SSE event stream; without it the one-shot JSON above is returned unchanged.

```bash
curl -N -H "Accept: text/event-stream" -H "Content-Type: application/json" \
  -d '{"content":"introduce yourself"}' \
  http://localhost:8080/api/v1/sessions/<id>/messages
```

Event types (`data:` is single-line JSON):

| event | data payload | Meaning |
| --- | --- | --- |
| `token` | `{"delta":"…"}` | Reply text increment (typewriter) |
| `tool_start` | `{"name":"shell"}` | Tool call started |
| `tool_end` | `{"name":"shell","success":true}` | Tool call finished |
| `done` | `{"reply":"full reply"}` | Terminal event (exactly one of done/error) |
| `error` | `{"code":500,"message":"…"}` | Terminal event for mid-stream failures |
| `: ping` | — (SSE comment line) | Heartbeat every `oryxos.web.sse.heartbeat-seconds` (default 15s); parsers should ignore it |

Semantic promises: concatenated `token` deltas equal `done.reply`; pre-stream failures (404/401/400) still return JSON status codes; if the client disconnects mid-stream the server completes the round anyway — history and audit rows are written as usual (no refund on disconnect); streaming and non-streaming write identical `llm_calls`/`tool_invocations` audit records. Full contract: `specs/019-sse-streaming/contracts/sse-protocol.md` in the repository.

### List sessions

**GET** `/api/v1/sessions?status=active`

Returns the most recent session summaries (up to 100, newest active first). The optional `status` query filters by status.

```json
// data — SessionSummaryView[]
[
  {
    "sessionId": "web:user-001:ops-agent",
    "profileName": "ops-agent",
    "channel": "web",
    "userId": "user-001",
    "status": "active",
    "createdAt": "2026-06-28T10:00:00Z",
    "lastActiveAt": "2026-06-28T10:01:02Z",
    "messageCount": 2
  }
]
```

### Get session history

**GET** `/api/v1/sessions/{id}`

Returns the most recent messages (up to 100). Unknown session id returns `404`.

```json
// data — SessionView
{
  "sessionId": "web:user-001:ops-agent",
  "profileName": "ops-agent",
  "messages": [
    { "role": "user",      "content": "查一下磁盘使用情况", "toolName": null, "toolCallId": null, "toolCalls": [] },
    { "role": "assistant", "content": "当前磁盘使用率 42%。", "toolName": null, "toolCallId": null, "toolCalls": [] }
  ]
}
```

### Archive a session

**DELETE** `/api/v1/sessions/{id}`

Marks the session as archived. Data is retained in SQLite; the session is excluded from active listings. Unknown session id returns `404`.

```json
// data
{ "archived": true }
```

---

## Schedules

Scheduled tasks are derived from the `schedules` block in each Agent's `AGENT.md`. New clients must use the v2 endpoints below: `scheduleId` is the stable runtime identity, while `key` is only Agent-local. The v1 endpoints in the following legacy section resolve a key only when it is unambiguous; otherwise they return `409`.

### v2 runtime API

- `GET /api/v2/schedules`
- `POST /api/v2/schedules/{scheduleId}/run`
- `PUT /api/v2/schedules/{scheduleId}`
- `GET /api/v2/schedules/{scheduleId}/executions?limit=20`
- `POST /api/v2/agents/{profileName}/schedules/{key}/run`

Each v2 schedule contains `scheduleId`, `profileName`, `key`, `name`, and its runtime state. Execution rows include `legacyMigrated` and `legacyTaskKey` for history imported from the pre-v2 schema.

### Deprecated v1 compatibility API

### List schedules

**GET** `/api/v1/schedules`

```json
// data — LegacyScheduleView[]
[
  {
    "taskId": "morning",
    "profileName": "weather-daily",
    "cron": "0 0 8 * * *",
    "zone": "Asia/Shanghai",
    "message": "推送今天的天气和穿衣建议",
    "enabled": true,
    "nextRunAt": "2026-07-23T00:00:00Z",
    "lastRunAt": "2026-07-22T00:00:00Z",
    "lastStatus": "success",
    "runCount": 12
  }
]
```

### List executions

**GET** `/api/v1/schedules/{id}/executions?limit=20`

Returns execution history for a task (`limit` caps the rows).

```json
// data — ExecutionView[]
[
  {
    "scheduleId": "ae3d1f7b-245c-4c37-bbf7-38a6db55bfce",
    "legacyTaskKey": null,
    "legacyMigrated": false,
    "sessionId": "schedule:ae3d1f7b-245c-4c37-bbf7-38a6db55bfce",
    "startedAt": "2026-07-22T00:00:00Z",
    "success": true,
    "errorMessage": null,
    "durationMs": 1842
  }
]
```

### Run now

**POST** `/api/v1/schedules/{id}/run`

Triggers the task immediately and returns the resulting execution record(s).

```json
// data — ExecutionView[]
[
  { "scheduleId": "ae3d1f7b-245c-4c37-bbf7-38a6db55bfce", "legacyTaskKey": null, "legacyMigrated": false, "sessionId": "schedule:ae3d1f7b-245c-4c37-bbf7-38a6db55bfce", "startedAt": "2026-07-22T09:30:00Z", "success": true, "errorMessage": null, "durationMs": 1730 }
]
```

### Enable / disable

**PUT** `/api/v1/schedules/{id}`

```json
// request — SetEnabledRequest
{ "enabled": false }
```

```json
// data — LegacyScheduleView[] (the updated schedule list)
[
  { "taskId": "morning", "profileName": "weather-daily", "cron": "0 0 8 * * *", "zone": "Asia/Shanghai", "message": "推送今天的天气和穿衣建议", "enabled": false, "nextRunAt": null, "lastRunAt": "2026-07-22T00:00:00Z", "lastStatus": "success", "runCount": 12 }
]
```

---

## Profiles

### List profiles

**GET** `/api/v1/profiles`

Returns the derived Profiles — one per Agent directory under `.oryxos/agents/`.

```json
// data — ProfileView[]
[
  {
    "name": "ops-agent",
    "description": "Operations assistant",
    "provider": "deepseek",
    "model": "deepseek-chat",
    "tools": ["read_file", "shell", "http_get", "save_memory", "recall_memory"]
  }
]
```

---

## Tools

### List available tools

**GET** `/api/v1/tools`

Returns all tools registered in the `ToolRegistry` — built-in tools plus any tools exposed by configured MCP servers.

```json
// data — ToolView[]
[
  { "name": "read_file", "description": "Read a file from the filesystem (path whitelist enforced)" },
  { "name": "shell",     "description": "Execute a shell command (command whitelist enforced; direct argv, no shell interpretation)" },
  { "name": "notify",    "description": "Push a message to a registered notify channel by name" }
]
```

---

## Sandbox whitelist

The sandbox whitelist has three categories — `FILE` (allowed paths), `SHELL` (allowed command tokens), and `HTTP` (allowed domains) — that can be adjusted at runtime. Changes take effect on the next tool call. The `{category}` path variable is case-insensitive (`file` / `shell` / `http`); an unknown category returns `400`.

### List the whitelist

**GET** `/api/v1/sandbox/whitelist`

Returns all three categories at once.

```json
// data
{
  "file": ["/home/user/project/.oryxos"],
  "shell": ["ls", "cat", "echo", "grep"],
  "http": ["*.open-meteo.com", "hn.algolia.com"]
}
```

### Add an entry

**POST** `/api/v1/sandbox/whitelist/{category}`

Adds one entry (idempotent) and returns whether it changed plus the category's latest full list.

```json
// request
{ "value": "*.example.com" }
```

```json
// data — WhitelistChange
{
  "category": "http",
  "value": "*.example.com",
  "changed": true,
  "entries": ["*.open-meteo.com", "hn.algolia.com", "*.example.com"]
}
```

### Remove an entry

**DELETE** `/api/v1/sandbox/whitelist/{category}?value=*.example.com`

The `value` is a **query parameter**; a blank value returns `400`.

```json
// data — WhitelistChange
{
  "category": "http",
  "value": "*.example.com",
  "changed": true,
  "entries": ["*.open-meteo.com", "hn.algolia.com"]
}
```

---

## Tool policy

The platform administrator's governance layer (020): global + per-agent tool allow/deny, separate from the author-owned `tools:` list in `AGENT.md`. Policy is subtraction-only — the effective tool set is always ⊆ the declared set; it is orthogonal to the sandbox whitelist (policy decides *whether an agent may use a tool*, the sandbox decides *what resources a tool may touch*). Zero rules = current behavior unchanged; rule changes take effect immediately (hot reload).

Three rule types: `GLOBAL_DENY` (all agents, no agentName), `AGENT_EXEMPT` (lifts the global deny for one agent), `AGENT_DENY` (tightens one agent — exemptions cannot save it). `pattern` is an exact tool name or an MCP server wildcard (`github-mcp:*`, matched by the tool's registered ownership). Three guards: denied tools never enter the model's tool list (before), the executor rejects by the latest policy at execution time (during, catches hallucinated calls), and `tool_invocations.blocked_by='policy'` marks the audit row (after, filterable).

### Get policy and effective tool sets

**GET** `/api/v1/tool-policy` — returns `rules` plus each agent's `declared` / `effective` / `removed` (with the matched-rule reason).

### Create a rule

**POST** `/api/v1/tool-policy/rules` — `{ "ruleType": "AGENT_EXEMPT", "agentName": "ops-agent", "pattern": "shell" }`. `GLOBAL_DENY` must not carry `agentName`, the other two must (`400` otherwise); duplicates return `409`; unknown tool names save with an `unknownTarget: true` warning flag.

### Delete a rule

**DELETE** `/api/v1/tool-policy/rules/{id}` — `404` if absent; takes effect immediately.

### Audit filter

**GET** `/api/v1/audit/tool?blockedBy=policy` — only tool calls rejected by policy.

---

## Audit trace

One message-processing round (session message / stateless invoke / scheduled trigger / Feishu inbound) = one trace ID (UUID), shared across all audit records of that round, structured logs (MDC `traceId` field), and the return channels — the same value everywhere, so audit and logs are cross-searchable. Enabled by default with zero configuration; pre-upgrade audit rows have an empty trace and existing queries are unaffected.

Three return channels (021, all purely additive):

| Channel | Shape |
|---------|-------|
| REST non-streaming | `traceId` field on `MessageResponse` |
| SSE streaming | first business event `event: trace` (`data: {"traceId":"…"}`) right after the stream opens; the `done` payload carries `traceId` too |
| Execution history | `traceId` field on `AgentExecutionView` (`GET /agents/{name}/executions`) |

### Single-round timeline

**GET** `/api/v1/audit/trace/{traceId}`

```json
// data — TraceTimelineView
{ "traceId": "3f9c2b1a-…", "found": true,
  "steps": [
    { "seq": 1, "type": "LLM",  "name": "glm-4-flash", "success": true, "durationMs": 1200,
      "at": "…", "promptTokens": 812, "completionTokens": 64, "totalTokens": 876, "costMicros": 120 },
    { "seq": 2, "type": "TOOL", "name": "save_memory", "success": true, "durationMs": 15,
      "at": "…", "inputSummary": "{\"content\":\"…\"}", "resultSummary": "OK", "blockedBy": null },
    { "seq": 3, "type": "LLM",  "name": "glm-4-flash", "success": true, "durationMs": 900, "at": "…", "totalTokens": 540 }
  ],
  "summary": { "steps": 3, "llmCalls": 2, "toolCalls": 1,
               "totalTokens": 1416, "costMicros": 260, "totalDurationMs": 2115 } }
```

Steps are sorted by occurrence time; failed and policy-blocked steps (`blockedBy: "policy"`) are part of the chain. A miss returns `found: false` with empty `steps` (HTTP 200, not an error). The existing list views of `GET /audit/llm|tool` gain a `traceId` field (row-level trace entry point). The admin console report page offers a trace search box with a timeline view; detail rows show a clickable traceId.

### Display-layer redaction

On the timeline, a TOOL step's `inputSummary`/`resultSummary`/`errorMessage` are **truncated (200 chars) + redacted** display values: known API key prefixes (`sk-…`/`oryx_…`), `Authorization` credentials, URL userinfo, and `password/secret/token/api_key`-style field values are masked to `first 4 chars + ****`. Stored rows keep the original text (full forensic context; DB access = ops privilege boundary). Rules are built in and non-configurable; content without sensitive shapes is shown as-is.

---

## Provider fallback & business metrics

**Fallback (023)**: the `provider` section of `AGENT.md` accepts an ordered fallback list — when a single LLM call hits a provider-side failure (network/timeout/5xx/429/401/403), the call retries the same request through the fallback chain in declared order; only when all candidates fail is the last error thrown. Business-level failures (400-class) never switch. Switching is scoped to a single LLM call (ReAct iteration semantics unchanged; every call starts from the primary — no cross-request health memory); streaming calls may switch only before the first content fragment is emitted. Every attempt writes its own `llm_calls` row (primary and fallback both audited, same trace), and each switch logs a WARN (from→to, with traceId). Zero declarations = zero behavior change.

**Business metrics (023)**: `GET /actuator/prometheus` (existing endpoint) gains `oryxos_`-prefixed metrics for enterprise monitoring stacks — `oryxos_llm_calls_total{provider,model,outcome}` (same cardinality as `llm_calls` rows), `oryxos_llm_call_duration_seconds`, `oryxos_llm_tokens_total{type}`, `oryxos_tool_invocations_total{tool,outcome}`, `oryxos_policy_blocks_total{tool}`, `oryxos_fallback_switches_total{from,to}`. Metrics serve aggregation and alerting; the audit tables remain the source of precise replay (orthogonal — metrics never change audit semantics). Absent or zero series simply mean the event has not occurred.

---

## Workspace file browser

A read-only directory tree plus per-file read/write over the workspace. The tree lists `agents/`, `skills/`, `output/` and `archive/` (a custom `readable-roots` makes the tree follow that setting; with the default configuration it stays these four); reads are allowed only under the configured readable roots (these four by default) and writes only under the configured writable roots (`agents/`, `output/` and `archive/` by default; `skills/` is a read-only view). All paths are relative to the workspace root; a path that escapes the root (path traversal), or one that is inside the root but outside the allowed roots, returns `400`. Both sets can be overridden with `oryxos.web.workspace.readable-roots` / `writable-roots` (comma-separated single-segment names of letters, digits, `_` and `-`; invalid values refuse startup, non-default values log a WARN at startup).

### Directory tree

**GET** `/api/v1/workspace/tree`

The root node is named after the workspace directory.

```json
// data — FileNode
{
  "name": ".oryxos",
  "path": "",
  "type": "dir",
  "children": [
    {
      "name": "agents",
      "path": "agents",
      "type": "dir",
      "children": [
        { "name": "AGENT.md", "path": "agents/ops-agent/AGENT.md", "type": "file", "children": [] }
      ]
    }
  ]
}
```

### Read a file

**GET** `/api/v1/workspace/file?path=agents/ops-agent/AGENT.md`

Returns the file content as a string. A path outside the root, or one inside the root but outside a readable root, returns `400`.

```json
// data — the file text
"---\nname: ops-agent\ndescription: Operations assistant\n---\n\nYou are an operations assistant..."
```

### Write a file

**POST** `/api/v1/workspace/file`

Same path guard as reading, applied to the writable roots (`agents/`, `output/`, `archive/` by default; `skills/` is a read-only view).

```json
// request — WriteFileRequest
{
  "path": "agents/ops-agent/AGENT.md",
  "content": "---\nname: ops-agent\n...\n---\n\n..."
}
```

```json
// data
null
```

---

## Not in the core phase

The following capabilities are planned for later phases:

- Authentication and RBAC (core phase assumes internal network)
- SSE streaming responses
- WebSocket connections
- Rate limiting
