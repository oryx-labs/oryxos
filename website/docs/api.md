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
{ "reply": "The last 10 commits covered ReAct Loop, provider abstraction, and SQLite persistence." }
```

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

> The `apiKey` is returned in **plaintext** by design (core-phase internal-network deployment).

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
  "apiKey": "sk-xxxxxxxx",
  "baseUrl": "https://api.deepseek.com",
  "description": "DeepSeek chat"
}
```

### List providers

**GET** `/api/v1/providers`

```json
// data — ProviderView[]
[
  { "name": "deepseek", "apiKey": "sk-xxxxxxxx", "baseUrl": "https://api.deepseek.com", "description": "DeepSeek chat" },
  { "name": "mock",     "apiKey": null,          "baseUrl": null,                       "description": "built-in fake model" }
]
```

### Get one provider

**GET** `/api/v1/providers/{name}`

Returns `404` if the provider does not exist.

```json
// data — ProviderView
{ "name": "deepseek", "apiKey": "sk-xxxxxxxx", "baseUrl": "https://api.deepseek.com", "description": "DeepSeek chat" }
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
{ "name": "deepseek", "apiKey": "sk-yyyyyyyy", "baseUrl": "https://api.deepseek.com", "description": "rotated key" }
```

### Delete a provider

**DELETE** `/api/v1/providers/{name}`

```json
// data
null
```

---

## Notify channels

Notify channels are managed dynamically and stored in SQLite (the `notify_channels` table). The `notify` tool references a channel **by name** in natural language inside an `AGENT.md` body (e.g. "发到 team-lark"); the tool resolves the registered channel to its adapter and URL. There is no `notify_channels` field in the AGENT.md frontmatter.

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

Scheduled tasks are derived from the `schedules` block in each Agent's `AGENT.md`. The web manager can list, inspect, trigger, and toggle them.

### List schedules

**GET** `/api/v1/schedules`

```json
// data — ScheduleView[]
[
  {
    "taskId": "weather-daily:morning",
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
    "taskId": "weather-daily:morning",
    "sessionId": "schedule:weather-daily:morning",
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
  { "taskId": "weather-daily:morning", "sessionId": "schedule:weather-daily:morning", "startedAt": "2026-07-22T09:30:00Z", "success": true, "errorMessage": null, "durationMs": 1730 }
]
```

### Enable / disable

**PUT** `/api/v1/schedules/{id}`

```json
// request — SetEnabledRequest
{ "enabled": false }
```

```json
// data — ScheduleView[] (the updated schedule list)
[
  { "taskId": "weather-daily:morning", "profileName": "weather-daily", "cron": "0 0 8 * * *", "zone": "Asia/Shanghai", "message": "推送今天的天气和穿衣建议", "enabled": false, "nextRunAt": null, "lastRunAt": "2026-07-22T00:00:00Z", "lastStatus": "success", "runCount": 12 }
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
  { "name": "shell",     "description": "Execute a shell command (command whitelist enforced)" },
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
  "shell": ["ls", "cat", "python3"],
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

## Workspace file browser

A read-only directory tree plus per-file read/write over the workspace (`agents/` and `archive/`). All paths are relative to the workspace root; a path that escapes the root (path traversal) returns `400`.

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

Returns the file content as a string. A path outside the root returns `400`.

```json
// data — the file text
"---\nname: ops-agent\ndescription: Operations assistant\n---\n\nYou are an operations assistant..."
```

### Write a file

**POST** `/api/v1/workspace/file`

Same path guard as reading.

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
