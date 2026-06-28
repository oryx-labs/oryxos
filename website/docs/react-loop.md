# ReAct Loop

OryxOS implements the ReAct (Reason + Act) loop from scratch. The loop is a few dozen lines of Java and gives you complete control over how your agents think and act — no black-box framework magic.

## How it works

```
User message
  → Append to Session conversation history
  → PromptBuilder assembles the prompt:
      [1] system prompt (Profile identity + Bootstrap + SKILL.md)  ← ContextLoader
      [2] long-term memory (MEMORY.md full text)                   ← MemoryService
      [3] conversation history (last max_history_turns turns)      ← SessionManager
      [4] available tool list (Function Calling format)            ← ToolRegistry
  → ProviderService calls the LLM
  → [No tool call] → return final response
  → [Tool call] → ToolExecutor executes the tool
      → SandboxChecker validates against whitelist
      → Execute (built-in tools in-process / MCP tools via JSON-RPC)
      → Write to tool_invocations audit table
      → Append result to conversation history
  → Loop back to PromptBuilder (up to max_iterations times)
```

## Key design decisions

**Self-implemented, not framework-delegated.** Spring AI's `ChatClient` automatic tool execution is explicitly disabled. The ReAct Loop and `ToolExecutor` own all tool scheduling and execution.

**Synchronous, not reactive.** The loop is synchronous and blocking throughout, relying on Java 21 Virtual Threads for concurrency. This keeps the code simple and debuggable.

**Audit by default.** Every LLM call writes to `llm_calls` and every tool invocation writes to `tool_invocations` — from day one.
