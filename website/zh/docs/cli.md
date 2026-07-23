# CLI 参考

`oryxos` 命令行工具提供 12 个子命令，用于管理工作区、Profile、会话和运行 Agent。

所有命令都支持 `--help`（`-h`）查看用法、`--version`（`-V`）打印版本及 JVM/OS 信息。

## 工作区根目录

默认在当前目录读写 `.oryxos` 工作区，根目录可自定义：

| 方式 | 作用范围 | 说明 |
| --- | --- | --- |
| `ORYXOS_ROOT`（环境变量） | 所有命令 | 轻命令直接读取；`serve`/`gateway` 经 Spring relaxed binding 也认 |
| `-Doryxos.root=<path>`（JVM 系统属性） | 所有命令 | 轻命令优先级最高 |
| `application.yml` 的 `oryxos.root` | 仅 `serve` / `gateway` | 轻命令（`init`/`status`/`profile`）不启动 Spring、不读 yaml，要给它们自定义根请用环境变量或系统属性 |

轻命令解析顺序：`-Doryxos.root` → `ORYXOS_ROOT` → 默认 `.oryxos`。配置的根目录会在运行时自动加入文件沙箱白名单，换根不会破坏文件工具。

```bash
# 让所有命令指向自定义工作区
ORYXOS_ROOT=/data/ws oryxos init
ORYXOS_ROOT=/data/ws oryxos status
```

## 初始化和状态

### oryxos init

初始化工作区（默认 `.oryxos/`）。在当前目录创建目录结构和默认 Bootstrap 文件。重复运行会跳过已有文件、绝不覆盖。这是轻命令：不启动 Spring，认 `ORYXOS_ROOT` / `-Doryxos.root`。

```bash
oryxos init
# 或指定自定义工作区根：
ORYXOS_ROOT=/data/ws oryxos init
```

创建的内容：

```text
.oryxos/
├── agents/       # 每个子目录 = 一个 Agent（AGENT.md + 可选 skills/ scripts/ REFERENCE.md）
├── memory/       # 全局长期记忆（每个 Agent 自己的 MEMORY.md 在 agents/<name>/ 下）
├── sessions/     # 备用（会话状态已入 SQLite）
├── logs/
├── AGENTS.md     # Bootstrap：项目级 agent 行为说明
├── SOUL.md       # Bootstrap：agent 人格定义
└── USER.md       # Bootstrap：用户偏好（只读）
```

`oryxos.db` 在首次运行重命令（`serve`/`gateway`/`chat`）时创建，`init` 本身不建库。

### oryxos status

查看工作区与数据文件状态：工作区是否初始化、`agents/` 下有多少个 Agent、SQLite 库是否已创建。与 `init` 一样是轻命令——不启动 Spring，认 `ORYXOS_ROOT` / `-Doryxos.root`。

```bash
oryxos status
```

示例输出：

```text
工作区 .oryxos/  : 已初始化
Agent 目录      : 3 个
SQLite 数据库   : oryxos.db 存在
```

## 对话

### oryxos chat

启动交互式多轮对话。每轮对话经过完整的 ReAct Loop，Agent 可调用工具后再回复。使用 `/quit` 或 `Ctrl-D` 退出。

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

启动 HTTP API 服务：对外暴露 `/api/v1` 下的 REST API，并在 `/admin/` 提供 Web 管理台。作为重命令，`serve` 会启动 Spring，从 `application.yml` 的 `oryxos.root` 读取工作区根（Spring relaxed binding 同样认 `ORYXOS_ROOT` 环境变量）。

```bash
oryxos serve [--port <port>]
```

| 选项 | 说明 |
| --- | --- |
| `--port <port>` | 监听端口，默认 `8080` |

示例：

```bash
oryxos serve --port 9090
# REST API:    http://0.0.0.0:9090/api/v1
# 管理台:      http://0.0.0.0:9090/admin/
```

### oryxos gateway

以守护进程模式启动（完整运行时常驻），适合生产多 Agent 部署；多 Channel 挂载属扩展阶段。与 `serve` 一样启动 Spring，从 `oryxos.root` 读取工作区根（经 relaxed binding 认 `ORYXOS_ROOT`）。`Ctrl-C` 退出。

```bash
oryxos gateway
```

## Agent（Profile）管理

每个 Agent 是 `<工作区>/agents/<name>/` 下的一个目录，内含 `AGENT.md`（YAML frontmatter = 这个 Agent 的 profile：identity/provider/tools/settings；正文 = 任务指令），外加可选的 `skills/`、`scripts/`、`REFERENCE.md`。一个目录 = 一个 Agent——**不再有 `.oryxos/profiles/` 目录**。`profile` 子命令都是轻命令（不启动 Spring），直接读写这些 `AGENT.md`，认 `ORYXOS_ROOT` / `-Doryxos.root`。

### oryxos profile list

列出 `<工作区>/agents/` 下所有 Agent（每行一个 Agent 目录名）。

```bash
oryxos profile list
```

### oryxos profile create

创建新 Agent，在 `<工作区>/agents/<name>/` 下生成带默认字段的最小 `AGENT.md` 模板；已存在则不覆盖。

```bash
oryxos profile create <name>
```

示例：

```bash
oryxos profile create my-agent
# 生成 .oryxos/agents/my-agent/AGENT.md
```

### oryxos profile show

打印指定 Agent 的 `AGENT.md` 完整内容。

```bash
oryxos profile show <name>
```

### oryxos profile delete

删除指定 Agent 的整个目录。

```bash
oryxos profile delete <name>
```

## 查询

### oryxos provider list

列出本实例声明的 Provider。轻命令直接从打包的 `application.yml`（`oryxos.providers` 段）读取，打印每个 Provider 的名字和 base URL。（运行时 Provider 由 SQLite 支撑的动态注册表管理，启动时以这些声明为种子。）

```bash
oryxos provider list
```

示例输出：

```text
deepseek     https://api.deepseek.com
qwen         https://dashscope.aliyuncs.com/compatible-mode/v1
```

### oryxos tool list

列出内置 Tool。运行时 `ToolRegistry` 接线后，此清单反映实时注册表（内置 + MCP Tool）。

```bash
oryxos tool list
```

示例输出：

```text
内置 Tool：
  read_file
  write_file
  list_dir
  shell
  http_get
  http_post
  save_memory
  recall_memory
  notify
```

### oryxos session list

列出 SQLite 库（当前目录的 `oryxos.db`）里的会话，按最后活跃时间倒序。字段：session id、profile 名、状态、最后活跃时间。

```bash
oryxos session list
```
