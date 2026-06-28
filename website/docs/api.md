# REST API

OryxOS exposes 10 REST endpoints under the `/api/v1` prefix. Authentication is not required in the core phase — it assumes an internal network deployment.

## Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/sessions` | Create a new session |
| `POST` | `/api/v1/sessions/{id}/messages` | Send a message (triggers the ReAct Loop) |
| `GET` | `/api/v1/sessions/{id}` | Get session history |
| `DELETE` | `/api/v1/sessions/{id}` | Archive a session |
| `POST` | `/api/v1/agents/{name}/invoke` | Stateless single-turn agent invocation |
| `GET` | `/api/v1/profiles` | List all profiles |
| `GET` | `/api/v1/memory` | Read long-term memory (MEMORY.md content) |
| `GET` | `/api/v1/tools` | List available tools |
| `GET` | `/api/v1/health` | Health check |
| `GET` | `/api/v1/info` | Runtime info and provider status |

## Example: create a session and send a message

```bash
# Create a session
curl -X POST http://localhost:8080/api/v1/sessions \
  -H "Content-Type: application/json" \
  -d '{"profile": "ops-agent", "channel": "api", "user_id": "user-001"}'

# Send a message
curl -X POST http://localhost:8080/api/v1/sessions/sess-001/messages \
  -H "Content-Type: application/json" \
  -d '{"content": "What is the current disk usage on the server?"}'
```

## Not implemented in the core phase

- Authentication and RBAC (assumes internal network)
- SSE streaming responses
- WebSocket connections
- Rate limiting
