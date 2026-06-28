# CLI 参考

`oryxos` 命令行工具提供 12 个子命令，用于管理工作区、Profile、会话和运行 Agent。

## 初始化和状态

### oryxos init

初始化 `.oryxos/` 工作区。在当前目录创建目录结构、默认配置文件和 SQLite 数据库。已存在工作区时会跳过已有文件，不会覆盖。

```bash
oryxos init
```

### oryxos status

查看当前工作区配置和运行状态，包括已加载的 Profile 数量、Provider 连通性、注册的 Tool 列表和活跃会话数。

```bash
oryxos status
```

示例输出：

```text
Workspace:  /home/user/myproject/.oryxos
Profiles:   3 loaded (ops-agent, default, pr-digest)
Providers:  deepseek [ok]  qwen [ok]
Tools:      9 registered (7 builtin, 2 mcp)
Sessions:   2 active
```

## 对话

### oryxos chat

启动交互式多轮对话。每轮对话经过完整的 ReAct Loop，Agent 可调用工具后再回复。使用 `/exit` 或 `Ctrl+C` 退出。

```bash
oryxos chat [--profile <name>]
```

| 选项 | 说明 |
| --- | --- |
| `--profile <name>` | 指定使用的 Profile，默认为 `default` |

示例：

```bash
oryxos chat --profile ops-agent
```

## 服务

### oryxos serve

启动 HTTP API 服务，对外暴露 10 个 REST 端点（`/api/v1`）。

```bash
oryxos serve [--port <port>]
```

| 选项 | 说明 |
| --- | --- |
| `--port <port>` | 监听端口，默认 `8080` |

示例：

```bash
oryxos serve --port 9090
```

### oryxos gateway

以守护进程模式启动，同时接入多个 Channel（CLI、API 等），适合生产部署场景。

```bash
oryxos gateway
```

## Profile 管理

### oryxos profile list

列出 `.oryxos/profiles/` 下所有 Profile 及其基本信息。

```bash
oryxos profile list
```

### oryxos profile create

创建新 Profile，在 `.oryxos/profiles/` 下生成带默认字段的 YAML 文件。

```bash
oryxos profile create <name>
```

示例：

```bash
oryxos profile create my-agent
# 生成 .oryxos/profiles/my-agent.yaml
```

### oryxos profile show

打印指定 Profile 的完整配置内容。

```bash
oryxos profile show <name>
```

### oryxos profile delete

删除指定 Profile 文件。

```bash
oryxos profile delete <name>
```

## 查询

### oryxos provider list

列出已配置的所有 Provider 及其连通状态。

```bash
oryxos provider list
```

示例输出：

```text
NAME        STATUS    MODEL
deepseek    ok        deepseek-chat
qwen        ok        qwen-max
```

### oryxos tool list

列出 ToolRegistry 中当前注册的所有可用 Tool，包括内置 Tool 和通过 MCP 接入的 Tool。

```bash
oryxos tool list
```

示例输出：

```text
NAME             SOURCE     DESCRIPTION
read_file        builtin    读取文件内容
write_file       builtin    写入文件内容
list_dir         builtin    列出目录内容
shell            builtin    执行 shell 命令
http_get         builtin    发起 HTTP GET 请求
http_post        builtin    发起 HTTP POST 请求
save_memory      builtin    追加内容到长期记忆
recall_memory    builtin    关键词检索长期记忆
search_issues    mcp        搜索 GitHub Issues
```

### oryxos session list

列出当前活跃的会话，包括 Session ID、关联 Profile、用户 ID 和最后活跃时间。

```bash
oryxos session list
```

## 全局选项

以下选项适用于所有子命令：

| 选项 | 说明 |
| --- | --- |
| `--workspace <path>` | 覆盖工作区路径，默认为 `./.oryxos` |
| `--log-level <level>` | 日志级别：`debug`、`info`、`warn`、`error`，默认 `info` |
