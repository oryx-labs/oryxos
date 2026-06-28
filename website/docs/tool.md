# Tool System

OryxOS has a unified tool abstraction and three tiers of extensibility — from zero-code SKILL.md to in-process Java `@Tool` beans.

## OryxTool interface

All tools implement the same interface:

```java
interface OryxTool {
    String getName();
    String getDescription();
    JsonSchema getInputSchema();
    ToolResult execute(JsonNode input);
}
```

`ToolResult` contains: `success`, `content`, `errorMessage`, `retryable`.

## Built-in tools (7 core tools)

| Tool | Class | Description |
|------|-------|-------------|
| `read_file` | `FileTools` | Read a file, path whitelist enforced |
| `write_file` | `FileTools` | Write a file, path whitelist enforced |
| `list_dir` | `FileTools` | List a directory, path whitelist enforced |
| `shell` | `ShellTools` | Execute bash, command whitelist + timeout |
| `http_get` | `HttpTools` | GET request, domain whitelist enforced |
| `http_post` | `HttpTools` | POST request, domain whitelist enforced |
| `save_memory` | `MemoryTools` | Append to MEMORY.md |
| `recall_memory` | `MemoryTools` | Keyword search over MEMORY.md |

## Three extension tiers

| Tier | Effort | Recommended | Implementation |
|------|--------|-------------|----------------|
| Zero-code | Lowest | ⭐ Primary | Write a SKILL.md, reuse community MCP servers |
| Light-code | Medium | ⭐⭐ | Write an MCP server in any language, configure in `mcp_servers.yaml` |
| Heavy-code | High | ⭐⭐⭐ | Java `@Tool` annotated Spring Bean, in-process execution |

## Sandbox

`SandboxChecker` validates all tool calls against configurable whitelists before execution:

- **File tools**: path whitelist (`file.allowed_paths`)
- **Shell tool**: first-token command whitelist (`shell.allowed_commands`)
- **HTTP tools**: domain wildcard whitelist (`http.allowed_domains`)
