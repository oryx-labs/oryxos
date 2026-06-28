# CLI 命令

`oryxos` 命令行工具提供 12 个子命令，用于管理工作区、Profile 和运行 Agent。

## 启动和状态

```bash
oryxos init                      # 初始化 .oryxos/ 工作区
oryxos status                    # 查看配置和运行状态
oryxos chat [--profile <name>]   # 交互式多轮对话（默认 profile: default）
oryxos serve [--port 8080]       # 启动 HTTP API 服务
oryxos gateway                   # 守护进程模式（多 Channel）
```

## Profile 管理

```bash
oryxos profile list
oryxos profile create <name>
oryxos profile show <name>
oryxos profile delete <name>
```

## 查询

```bash
oryxos provider list             # 列出已配置的 Provider 及其状态
oryxos tool list                 # 列出 ToolRegistry 中的可用工具
oryxos session list              # 列出活跃的会话
```

## 全局选项

| 选项 | 说明 |
|------|------|
| `--profile <name>` | 指定 Profile（默认：`default`） |
| `--workspace <path>` | 覆盖工作区路径（默认：`./.oryxos`） |
| `--log-level <level>` | 设置日志级别：`debug`、`info`、`warn`、`error` |
