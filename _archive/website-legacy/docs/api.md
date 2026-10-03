# REST API

OryxOS exposes 10 REST endpoints under the `/api/v1` prefix. No authentication is required in the core phase — deployment assumes an internal network. All request and response bodies are JSON.

Start the server with:

```bash
oryxos serve --port 8080
```

---

## Session management

Sessions are stateful conversations between a user and an agent. Each session carries message history and is tied to a Profile.

### Create a session

**POST** `/api/v1/sessions`

```json
{
  "profile": "ops-agent",
  "channel": "api",
  "user_id": "user-001"
}
```

```json
// 201 Created
{
  "session_id": "sess-a1b2c3",
  "profile": "ops-agent",
  "channel": "api",
  "user_id": "user-001",
  "status": "active",
  "created_at": "2025-06-01T10:00:00Z"
}
```

### Send a message

**POST** `/api/v1/sessions/{id}/messages`

Appends the user message to the session history and runs the ReAct Loop. Blocks until the agent produces a final response (synchronous).

```json
{
  "content": "What is the current disk usage on the server?"
}
```

```json
// 200 OK
{
  "role": "assistant",
  "content": "Current disk usage: /dev is at 42% (84G used of 200G). All other mounts are below 30%.",
  "tool_calls": [
    {
      "tool": "shell",
      "input": { "command": "df -h" },
      "result": "Filesystem  Size  Used  Avail  Use%  Mounted on\n/dev/sda1   200G   84G   116G   42%   /",
      "duration_ms": 312
    }
  ],
  "iterations": 2,
  "created_at": "2025-06-01T10:00:05Z"
}
```

### Get session history

**GET** `/api/v1/sessions/{id}`

```json
// 200 OK
{
  "session_id": "sess-a1b2c3",
  "profile": "ops-agent",
  "status": "active",
  "created_at": "2025-06-01T10:00:00Z",
  "last_active_at": "2025-06-01T10:00:05Z",
  "messages": [
    { "role": "user",      "content": "What is the current disk usage on the server?" },
    { "role": "assistant", "content": "Current disk usage: /dev is at 42%..." }
  ]
}
```

### Archive a session

**DELETE** `/api/v1/sessions/{id}`

Marks the session as `archived`. The data is retained in SQLite; the session is excluded from active listings.

```json
// 200 OK
{
  "session_id": "sess-a1b2c3",
  "status": "archived",
  "archived_at": "2025-06-01T11:00:00Z"
}
```

---

## Agent invocation

### Stateless invoke

**POST** `/api/v1/agents/{name}/invoke`

Runs a single-turn ReAct Loop without creating a persistent session. Useful for one-off tasks or function-style integrations where you don't need conversation history.

```json
{
  "message": "Summarize the last 10 git commits in this repo.",
  "context": {}
}
```

```json
// 200 OK
{
  "response": "The last 10 commits covered: ReAct Loop implementation (3), provider abstraction (2), SQLite persistence (3), CLI scaffolding (2).",
  "tool_calls": [],
  "iterations": 1
}
```

---

## Profile management

### List profiles

**GET** `/api/v1/profiles`

Returns all Profile YAMLs found in `.oryxos/profiles/`.

```json
// 200 OK
{
  "profiles": [
    {
      "name": "ops-agent",
      "description": "Operations assistant",
      "provider": "deepseek",
      "model": "deepseek-chat",
      "tools": ["read_file", "shell", "http_get"]
    },
    {
      "name": "default",
      "description": "General purpose assistant",
      "provider": "qwen",
      "model": "qwen-max",
      "tools": ["read_file", "http_get", "save_memory", "recall_memory"]
    }
  ]
}
```

---

## Memory

### Get long-term memory

**GET** `/api/v1/memory`

Returns the raw content of `.oryxos/memory/MEMORY.md`. This file is appended to by agents via the `save_memory` tool and injected into the system prompt on each turn.

```json
// 200 OK
{
  "content": "## 2025-06-01\n- User prefers concise responses\n- Deployment target is Ubuntu 22.04\n",
  "size_bytes": 512,
  "last_modified": "2025-06-01T10:00:05Z"
}
```

---

## Tools

### List available tools

**GET** `/api/v1/tools`

Returns all tools registered in the `ToolRegistry` — built-in tools plus any tools exposed by configured MCP servers.

```json
// 200 OK
{
  "tools": [
    {
      "name": "read_file",
      "description": "Read a file from the filesystem (path whitelist enforced)",
      "source": "builtin"
    },
    {
      "name": "shell",
      "description": "Execute a shell command (command whitelist enforced)",
      "source": "builtin"
    },
    {
      "name": "github_create_pr",
      "description": "Create a pull request on GitHub",
      "source": "mcp:github-mcp"
    }
  ]
}
```

---

## System

### Health check

**GET** `/api/v1/health`

Lightweight liveness probe. Returns `200 OK` when the server is running.

```json
// 200 OK
{
  "status": "ok",
  "uptime_ms": 83421
}
```

### Runtime info

**GET** `/api/v1/info`

Returns runtime details and the status of each configured provider.

```json
// 200 OK
{
  "version": "0.1.0",
  "java_version": "21.0.3",
  "workspace": "/home/user/project/.oryxos",
  "db": "oryxos.db",
  "providers": [
    { "name": "deepseek", "status": "ok", "model": "deepseek-chat" },
    { "name": "qwen",     "status": "ok", "model": "qwen-max" }
  ],
  "mcp_servers": [
    { "name": "github-mcp", "status": "connected", "tools": 8 }
  ]
}
```

---

## Not in the core phase

The following capabilities are planned for later phases:

- Authentication and RBAC (core phase assumes internal network)
- SSE streaming responses
- WebSocket connections
- Rate limiting
