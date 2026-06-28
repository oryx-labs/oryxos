# Roadmap

OryxOS is built in a four-week core phase, followed by extension phases.

## Four-week implementation schedule

| Week | Core tasks | Modules | Demo |
|------|-----------|---------|------|
| Week 1 | Provider abstraction + ReAct Loop | `oryxos-core` `oryxos-provider` `oryxos-channel-cli` `oryxos-cli` | `oryxos chat`: weather and outfit query |
| Week 2 | Memory + Tool system | `oryxos-memory` `oryxos-tool` | Cross-session preference memory; zero-code PR digest |
| Week 3 | Web Service | `oryxos-web` `oryxos-storage` | 10 REST endpoints fully functional |
| Week 4 | Multi-agent demo + engineering | All modules | Multi-agent coexistence; session persistence across restarts |

## Planned extension features

- **SSE streaming** — real-time token streaming in chat responses
- **Episodic memory** — structured event log with semantic retrieval
- **RBAC** — role-based access control for the REST API
- **WebSocket channel** — persistent two-way agent connections
- **Rate limiting** — per-user and per-provider throttling
- **Agent-to-Agent messaging** — direct agent-to-agent task delegation
- **Workflow engine** — multi-step declarative agent workflows
- **Web UI** — browser-based chat and admin dashboard
