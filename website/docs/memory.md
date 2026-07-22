# Memory

OryxOS gives agents memory across three layers. Each layer has a different scope, lifetime, and storage backend. `MemoryService` is the unified facade that `ReActLoop` and `PromptBuilder` talk to — they do not access layers directly.

## Three layers

| Layer | Storage | Scope | In core phase |
| --- | --- | --- | --- |
| Session memory | SQLite `sessions.messages_json` | Current conversation only | Yes |
| Long-term memory | `.oryxos/agents/<name>/MEMORY.md` (per-agent) | Persists across sessions, per agent | Yes |
| Episodic memory | Vector database (planned) | Semantic search across past sessions | No |

**Session memory** is the conversation history — the list of messages accumulated in the current session. It is serialized as JSON and stored in SQLite. `PromptBuilder` injects the last `max_history_turns` turns into every prompt. It accumulates automatically; agents do not write to it explicitly.

**Long-term memory** is an append-only, timestamped Markdown file agents write to using the `save_memory` tool. It persists across sessions and is injected into every prompt. Agents use it to remember user preferences, project facts, and anything worth keeping past the end of a conversation.

**Episodic memory** is planned for the extension phase. It will index past conversations in a vector database and retrieve semantically relevant episodes at prompt-build time.

## Long-term memory is per-agent

Long-term memory follows the "one directory = one Agent" principle: each agent has its own memory file at `.oryxos/agents/<name>/MEMORY.md`, alongside that agent's `AGENT.md` and `skills/`. When there is no agent context (a direct, non-agent-triggered call, or a unit test), it falls back to the global `.oryxos/memory/MEMORY.md`. The agent name is validated; any unsafe segment falls back to the global file to prevent path traversal.

The current agent is resolved from the execution context — the `save_memory` / `recall_memory` tool path sets it via `ToolExecutionContext`, and the read path (`buildContext` / per-agent read) sets it temporarily around the load.

### Timestamps and two sections

Every entry is written as a **timestamped** line, `- [yyyy-MM-dd HH:mm:ss] <content>`. A `MEMORY.md` file is organized into two sections:

- `## 核心记忆` (**core**) — always injected in full, never truncated
- `## 归档记忆` (**archival**) — the running footprint; truncated when it grows too large

`save_memory` targets one of these sections (`core` or `archival`). Recall and truncation operate on the **archival** section only; the core section is physically isolated and always kept intact.

### Automatic trigger footprint

Every time an agent is triggered, OryxOS automatically appends one line to **that agent's archival memory** — a compact record of the user message and the reply. This gives each agent an auditable trail of what it has done, without the agent having to call `save_memory` itself.

## Long-term memory operations

`LongTermMemoryStore` (default backend: `MarkdownMemoryStore`) exposes these operations:

| Method | Behavior |
| --- | --- |
| `append(content, scope)` | Appends a timestamped entry to the core or archival section |
| `load()` | Returns both sections; archival is truncated if oversized, core is returned in full |
| `recallByKeyword(keyword)` | Returns archival lines containing the keyword |
| truncation | If the archival section exceeds 4 000 chars, trims from the top, keeping the most recent content |

The 4 000-character limit prevents the archival section from consuming a disproportionate share of the context window. When truncation occurs, the oldest archival entries are dropped first — recency is prioritized. The core section is never touched.

The interface is designed with an upgrade path to vector search in mind. `recallByKeyword` can be replaced with a semantic search implementation without changing `MemoryService` or any caller.

## Pluggable backends

The long-term memory backend is pluggable via the `memory.backend` config key — swapping it only changes the injected `LongTermMemoryStore`; the `MemoryService` facade signature and every caller stay the same.

| `memory.backend` | Store | Notes |
| --- | --- | --- |
| `markdown` (default) | `MarkdownMemoryStore` | Zero-dependency, human-readable, git-trackable Markdown file |
| `sqlite` | `SqliteMemoryStore` | Entries stored row-by-row in the `memory_entries` table; a structured upgrade for larger memory volumes, still zero external dependencies (reuses the existing SQLite) |
| `mem0` | `Mem0MemoryStore` | Delegates to an external mem0 memory service |

## save_memory and recall_memory tools

Agents interact with long-term memory through two built-in tools, not by writing to `MEMORY.md` directly.

**`save_memory`** — appends a piece of text to the current agent's `MEMORY.md`, into the `core` or `archival` section. The agent decides what to save, where, and when. There is no automatic summarization in core phase.

```text
Tool: save_memory
Input: { "content": "User prefers concise responses, no bullet lists.", "scope": "archival" }
Effect: appends a timestamped entry to the agent's MEMORY.md archival section
```

**`recall_memory`** — searches the archival section by keyword and returns matching lines. Useful when the agent needs to check whether it already knows something before saving a duplicate.

```text
Tool: recall_memory
Input:  { "keyword": "prefers" }
Output: "- [2026-07-22 09:30:00] User prefers concise responses, no bullet lists."
```

Both tools go through `ToolExecutor` and are recorded in `tool_invocations` like any other tool call.

## Reading memory over REST

The admin console reads a specific agent's long-term memory through:

```text
GET /api/v1/agents/{name}/memory
```

The response (wrapped in the unified `{ code, message, data, timestamp }` envelope) carries the agent's `MEMORY.md`, which the console renders as **two tables**: 核心记忆 (core) and 归档记忆 (archival).

## How memory is injected into prompts

`PromptBuilder` calls `MemoryService.buildContext(session)` on every loop iteration and inserts the result as the second block of the system prompt, after the Agent identity and Bootstrap files:

```text
[1] system prompt  (AGENT.md body + bootstrap)
[2] MEMORY.md  ← injected here: core in full + archival truncated at 4 000 chars
[3] conversation history (last N turns)
[4] available tools (JSON Schema)
```

The memory block is always present, even if `MEMORY.md` is empty.

## MEMORY.md vs USER.md

These two files look similar but serve opposite purposes:

| | `MEMORY.md` | `USER.md` |
| --- | --- | --- |
| Who writes it | The agent (via `save_memory`, plus the auto trigger footprint) | The user (manually) |
| OryxOS behavior | Read + write | Read only |
| Contents | Agent's accumulated observations, learned facts, and run history | User's initial preferences and context |
| Resets | Never (append-only; archival truncation only when too large) | Never (managed by user) |
| Location | `.oryxos/agents/<name>/MEMORY.md` (per-agent) | `.oryxos/USER.md` |

`USER.md` is part of the Bootstrap group injected at position [1] in the prompt. `MEMORY.md` is injected at position [2]. Both are visible to the agent; only `MEMORY.md` can be modified by the agent.

## What is planned

- **Episodic memory** — index full session transcripts in a vector database; retrieve the top-k relevant episodes at prompt-build time
- **Vector search for recall** — replace `recallByKeyword` with embedding-based semantic search against `MEMORY.md` content
- **Memory summarization** — automatically compress old entries before truncation to preserve more signal in fewer characters
