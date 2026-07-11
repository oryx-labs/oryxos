# Data Model: Tool 体系

**Date**: 2026-07-11 | 无持久化变更（审计走 tool_invocations 既有表）

## 值对象与配置

### McpServerConfig（record）

| 字段 | 类型 | 说明 |
|---|---|---|
| name | String | server 标识（日志与告警点名用） |
| transport | String | 核心阶段支持 `stdio`；其余值 WARN 跳过 |
| command | String | 启动命令，空格拆分为可执行+args |
| env | Map\<String,String\> | 子进程环境变量，值支持 `${ENV}` 占位 |

### .oryxos/mcp_servers.yaml（推定格式，停点确认）

```yaml
servers:
  - name: github-mcp
    transport: stdio
    command: npx -y @modelcontextprotocol/server-github
    env:
      GITHUB_PERSONAL_ACCESS_TOKEN: ${GITHUB_TOKEN}
```

文件缺失 = 零 server，启动照常。

### Sandbox 前向类型（io.oryxos.tool.sandbox，实现归 24 节）

- `Sandbox`：`void enforce(SandboxAction action)`——不过即抛 `SandboxViolationException`。
- `SandboxAction`：record`(ActionType type, String target)`；target = 路径/完整命令/URL。
- `ActionType`：`FILE_READ / FILE_WRITE / SHELL_COMMAND / HTTP_REQUEST`。
- `PermissiveSandbox`：24 节前的临时装配，放行但每次 WARN。

## 内置工具输入面（@Tool 注解自动生成 schema）

| 工具 | 参数 | 检查位 |
|---|---|---|
| read_file | path | FILE_READ(path) |
| write_file | path, content | FILE_WRITE(path) |
| list_dir | path | FILE_READ(path) |
| shell | command | SHELL_COMMAND(command)；30s 超时 |
| http_get | url | HTTP_REQUEST(url) |
| http_post | url, body | HTTP_REQUEST(url) |

## ToolRegistry 不变量

- 名称唯一：重名 register 抛 IllegalStateException 点名。
- `filterByNames(list)`：返回"list 中存在于注册表"的精确子集（顺序按 list）；未知名跳过不报错。
- 三条来源路径：register（notify/MCP adapter）、registerAnnotated（内置/方式三）、MCP connectAll（内部走 register）。
