# Memory

OryxOS provides a three-layer memory system. Each layer serves a different purpose and has a different lifetime.

## Three layers

| Layer | Storage | Lifetime | Owner |
|-------|---------|----------|-------|
| **Session memory** | SQLite `sessions.messages_json` | Current session | SessionManager |
| **Long-term memory** | `.oryxos/memory/MEMORY.md` | Persistent across sessions | MemoryService |
| **Episodic memory** | — | Roadmap | — |

## Long-term memory tools

Agents write to long-term memory using the `save_memory` tool:

```bash
Tool: save_memory
Input: {"content": "User prefers dark mode and uses macOS"}
```

Agents recall from long-term memory using the `recall_memory` tool:

```bash
Tool: recall_memory
Input: {"query": "user preferences"}
Output: "User prefers dark mode and uses macOS"
```

## Automatic injection

Long-term memory (`MEMORY.md`) is automatically injected into every system prompt by `PromptBuilder`. The agent always has access to what it has learned — without being asked to recall it explicitly.

## MEMORY.md vs USER.md

- `USER.md`: Written by the user, read-only for OryxOS. Contains initial preferences and context.
- `MEMORY.md`: Written by the agent via `save_memory`. Contains what the agent has learned over time.
