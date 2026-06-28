# Tool 体系

OryxOS 有统一的 Tool 抽象和三档扩展能力——从零代码 SKILL.md 到进程内 Java `@Tool` Bean。

## OryxTool 接口

所有 Tool 实现同一个接口：

```java
interface OryxTool {
    String getName();
    String getDescription();
    JsonSchema getInputSchema();
    ToolResult execute(JsonNode input);
}
```

`ToolResult` 包含：`success`、`content`、`errorMessage`、`retryable`。

## 内置 Tool（7 个核心工具）

| Tool | 类 | 说明 |
|------|-----|------|
| `read_file` | `FileTools` | 读文件，路径白名单 |
| `write_file` | `FileTools` | 写文件，路径白名单 |
| `list_dir` | `FileTools` | 列目录，路径白名单 |
| `shell` | `ShellTools` | 执行 bash，命令白名单 + 超时 |
| `http_get` | `HttpTools` | GET 请求，域名白名单 |
| `http_post` | `HttpTools` | POST 请求，域名白名单 |
| `save_memory` | `MemoryTools` | 追加到 MEMORY.md |
| `recall_memory` | `MemoryTools` | 关键词检索 MEMORY.md |

## Plugin Tool 三档

| 方式 | 门槛 | 推荐 | 实现 |
|------|------|------|------|
| 零代码 | 最低 | ⭐ 主推 | 写 SKILL.md + 复用社区 MCP server |
| 轻代码 | 中 | ⭐⭐ | 任意语言写 MCP server，配置在 `mcp_servers.yaml` |
| 重代码 | 高 | ⭐⭐⭐ | Java `@Tool` 注解 Spring Bean，进程内直接调用 |

## 沙箱

`SandboxChecker` 在执行前根据可配置白名单校验所有工具调用：

- **文件工具**：路径白名单（`file.allowed_paths`）
- **Shell 工具**：命令首 token 白名单（`shell.allowed_commands`）
- **HTTP 工具**：域名通配符白名单（`http.allowed_domains`）
