# 工具体系

Agent 能调用的每一个能力都是一个 `OryxTool`。无论是内置的文件读取器、HTTP 客户端、记忆操作，还是远程 MCP server，接口都是统一的。`ToolExecutor` 和 `ToolRegistry` 不关心工具具体做什么，它们只和接口打交道。

## OryxTool 接口

```java
interface OryxTool {
    String getName();
    String getDescription();
    JsonSchema getInputSchema();
    ToolResult execute(JsonNode input);
}
```

`getInputSchema()` 返回一个 JSON Schema 对象，`ProviderService` 将其序列化为 LLM 能理解的 Function Calling 格式。Schema 告诉模型这个工具接受哪些参数以及各参数的含义。

`execute(JsonNode input)` 接收模型提供的参数，执行工具，返回 `ToolResult`：

```java
record ToolResult(
    boolean success,
    String  content,       // 返回给模型的输出
    String  errorMessage,  // success = false 时填充
    boolean retryable      // true = 追加错误信息，让模型在下次迭代中重试
) {}
```

`retryable` 为 `true` 时，`ToolExecutor` 将错误追加到对话历史，循环继续。`retryable` 为 `false` 时，循环立即将错误返回给调用方。

## 内置工具

OryxOS 核心内置约两打工具——一组精心挑选的**通用原语**，覆盖绝大多数 Agent 需求而不锁死任何领域。启动时自动注册。Agent 能用哪些工具，取决于其 Profile 的 `tools` 列表。

| 工具 | 类 | 说明 | 沙箱校验 |
| --- | --- | --- | --- |
| `read_file` | `FileTools` | 从磁盘读取文件 | 路径白名单 |
| `write_file` | `FileTools` | 写入或覆盖文件 | 路径白名单 |
| `edit_file` | `FileTools` | 替换文件中一段唯一片段 | 路径白名单 |
| `append_file` | `FileTools` | 向文件追加内容 | 路径白名单 |
| `list_dir` | `FileTools` | 列出目录内容 | 路径白名单 |
| `glob` | `FileTools` | 按 glob 通配查找文件 | 路径白名单 |
| `grep` | `FileTools` | 正则搜索文件内容 | 路径白名单 |
| `make_dir` | `FileTools` | 创建目录 | 路径白名单 |
| `move_file` | `FileTools` | 移动 / 重命名文件 | 路径白名单（源 + 目标） |
| `copy_file` | `FileTools` | 复制文件 | 路径白名单（源 + 目标） |
| `delete_file` | `FileTools` | 删除文件（拒绝删目录） | 路径白名单 |
| `shell` | `ShellTools` | 执行 Shell 命令 | 命令首 token 白名单 + 超时 |
| `http_get` / `http_post` | `HttpTools` | HTTP GET / POST | 域名通配符白名单 |
| `http_request` | `HttpTools` | 任意方法 HTTP（GET/POST/PUT/PATCH/DELETE）+ 请求头 | 域名通配符白名单 |
| `fetch_webpage` | `HttpTools` | 抓取网页并抽取可读正文（去 HTML） | 域名通配符白名单 |
| `download_file` | `HttpTools` | 下载 URL 到本地文件 | 域名 + 路径白名单 |
| `web_search` | `WebSearchTools` | 网络搜索 | 域名通配符白名单 |
| `current_time` | `UtilTools` | 返回指定时区的当前时间 | 无（纯计算） |
| `json_extract` | `UtilTools` | 从 JSON 文本按路径取值 | 无（纯计算） |
| `save_memory` | `MemoryTools` | 向 `MEMORY.md` 追加文本 | 无（始终允许） |
| `recall_memory` | `MemoryTools` | 关键词检索 `MEMORY.md` | 无（始终允许） |
| `notify` | `NotifyTools` | 向一个已注册的通知渠道推送消息 | 按名解析渠道 |
| `ask_user` | `InteractionTools` | 向用户提问（交互式渠道） | 无 |

`ShellTools` 除命令白名单外还强制执行可配置的超时时间（默认 30 秒）。超时的进程会被终止，工具返回不可重试的错误。

## `notify` 工具与通知渠道

`notify` 向一个**按名引用的通知渠道**推送消息。工具接收 `channel` 参数（渠道的注册名）和要发送的 `content`；它把这个名字解析为已注册的渠道，按渠道类型选择适配器，投递到渠道配置的 URL。

通知渠道是一等资源——通过 **`/api/v1/notify-channels`** 接口或 Web 管理台的「Notify 渠道」页面增删改，存于 SQLite 的 `notify_channels` 表。每个渠道有 `name`、`type`、`url` 和可选的 `description`。支持的类型：

| 类型 | 投递到 |
| --- | --- |
| `feishu` | 飞书 / Lark 群机器人 webhook |
| `wecom` | 企业微信群机器人 webhook |
| `dingtalk` | 钉钉群机器人 webhook |
| `webhook` | 通用 HTTP webhook |

Agent 在其 **`AGENT.md` 正文**里用自然语言按名引用渠道——例如「调用 notify，把报告发到 `team-lark`」。**AGENT.md frontmatter 中没有 `notify_channels` 字段**；渠道在调用时从注册表解析，因此增加或改指渠道都不用动任何 Agent。

## 三档扩展方式

内置工具之外的能力扩展，根据你愿意写多少代码，走三种模式之一：

| | 零代码 | 轻代码 | 重代码 |
| --- | --- | --- | --- |
| **方式** | 写一份 `SKILL.md` + 接入现有社区 MCP server | 用任意语言写一个 MCP server，加到 `mcp_servers.yaml` | 在 Java 模块里实现 `OryxTool` 并注册为 Spring `@Bean` |
| **运行位置** | MCP server 作为子进程或远程进程运行 | 同零代码，只是 server 是你自己写的 | 在 OryxOS JVM 进程内运行 |
| **适用场景** | 标准集成：GitHub、Slack、数据库、网络搜索 | 领域特定逻辑、内部系统、私有 API | 性能敏感的工具，或需要直接访问 OryxOS 内部的工具 |
| **推荐对象** | 大多数生产场景 | 中等集成复杂度 | 有 Java 经验的深度用户 |

零代码是首选方案。社区 MCP 生态已经覆盖了大多数常见集成。`SKILL.md` 为 Agent 提供如何使用这些工具的指令，MCP server 提供工具本身。

`mcp_servers.yaml` 中的 MCP server 配置示例：

```yaml
servers:
  - name: github-mcp
    command: npx
    args: ["-y", "@modelcontextprotocol/server-github"]
    env:
      GITHUB_TOKEN: ${GITHUB_TOKEN}

  - name: my-internal-api
    command: python3
    args: ["/opt/tools/my_mcp_server.py"]
    env:
      API_BASE: ${INTERNAL_API_BASE}
```

OryxOS 在启动时将每个 MCP server 作为子进程启动，通过 stdio 上的 JSON-RPC 通信。server 暴露的工具以其声明的名称注册到 `ToolRegistry` 中。

> **配置 Schema。** `McpConfigLoader` 解析顶层的 `servers:` 列表，每个条目有四个字段：`name`、`transport`（核心阶段只支持 `stdio`——`http`/`sse` 条目在启动时被跳过并 WARN）、`command`（单个字符串，按空白切分成可执行文件 + 参数，**没有独立的 `args:` 字段**）、`env`（映射）。`${ENV_VAR}` 占位符**只在 `env:` 的值里解析**，不在 `command:` 里解析——因此密钥应放在 `env:`，绝不能内联写进 `command:`。

## 推荐 MCP 服务器

OryxOS 刻意让内置工具保持精简。内置工具是**通用原语**——文件、Shell、HTTP、记忆、通知。一切领域特定的能力都通过 **MCP server** 抵达 Agent：GitHub、GitLab、Slack、Google Drive、Notion、浏览器自动化、网络搜索、错误追踪——并且刻意地，**也包括 SQL 数据库（PostgreSQL、SQLite……）**。OryxOS 不提供内置 SQL 工具；数据库属于领域集成，所以你接入它的 MCP server，而不是把查询工具塞进核心。这是既定的零/低代码扩展路径：新增一个能力靠的是写一个包名，而不是写 Java。

为了落到实处，OryxOS 附带一份精选、可直接复制的目录：**`config/mcp_servers.yaml.example`**。把它复制到 `.oryxos/mcp_servers.yaml`，只取消注释你需要的 server（默认全部注释掉，全新复制不会连接任何东西），为每个 server 设置所需的环境变量，然后重启。该目录严格匹配加载器的 Schema，列出以下类别里真实、知名的社区 server：

| 类别 | 目录中的 server |
| --- | --- |
| 代码托管 | GitHub、GitLab |
| 数据库（SQL） | PostgreSQL、SQLite |
| 即时通讯 | Slack |
| 文档 / 存储 | Google Drive、Notion |
| 文件系统 | Filesystem（比内置工具更丰富的操作） |
| 浏览器自动化 | Playwright（推荐）、Puppeteer |
| 网络搜索 | Brave Search |
| 可观测性 | Sentry |

每个条目都带有关于前置依赖（主机上需有 Node.js/`npx` 或 `uv`/`uvx`）、凭证处理的注释，并在相关处说明厂商是否已提供官方替代品、或提供了核心阶段仅支持 stdio 传输尚无法使用的远程（HTTP）server。凡是确切包名可能已变更之处，目录都会在注释里如实说明，而不是凭空猜测。

## 沙箱

`SandboxChecker` 在每次工具调用前运行，执行在 `application.yml` 里以顶层键配置的三个独立白名单。空列表表示 **deny-all**——沙箱默认关闭，需按最小权限显式放开。配置的工作区根目录会在运行期自动纳入文件白名单。

**文件路径白名单** — 作用于 `read_file`、`write_file`、`list_dir`。请求的路径必须匹配至少一条白名单项。

```yaml
file:
  allowed_paths:
    - .oryxos
    - /tmp/oryxos
```

**Shell 命令白名单** — 作用于 `shell`。只检查命令的第一个 token（可执行文件名），参数不受白名单限制。

```yaml
shell:
  allowed_commands:
    - ls
    - cat
    - grep
    - python3
  timeout_seconds: 30
```

**HTTP 域名白名单** — 作用于 `http_get` 和 `http_post`。支持 `*` 作为前缀通配符。

```yaml
http:
  allowed_domains:
    - "*.feishu.cn"
    - "api.deepseek.com"
    - "api.open-meteo.com"
```

这三个白名单也可在**运行期管理**——通过 `/api/v1/sandbox/whitelist` 接口和管理台，在 `FILE`、`SHELL`、`HTTP` 三类下增删条目，无需重启。

工具调用未通过沙箱校验时，`ToolExecutor` 返回不可重试的 `ToolResult`，并带有清晰的错误信息说明违反了哪条白名单。该调用仍会记录在 `tool_invocations` 里，`success = false`。

不使用 `SecurityManager`。它从 JDK 17 起废弃，在 JDK 21 上已不可用。沙箱完全在应用层实现：工具代码运行前，`SandboxChecker` 已完成白名单校验。

## 工具注册表

`ToolRegistry` 是所有工具——内置工具和 MCP 工具——的集中目录，承担两项职责：

**注册** — 启动时，所有 `OryxTool` Bean 完成注册，随后通过 JSON-RPC `tools/list` 发现 MCP server 提供的工具，并将其包装为 `McpProxyTool` 实例。

**过滤** — `PromptBuilder` 请求某个 Agent 可用的工具时，`ToolRegistry.getToolsForProfile(profile)` 只返回 `profile.tools` 列表中包含的工具。即使工具已全局注册，Agent 也无法调用不在其 Profile 里的工具。

```java
// PromptBuilder 用这个来为 LLM 构建 Function Calling 工具列表
List<OryxTool> tools = toolRegistry.getToolsForProfile(profile);
```

工具名称全局唯一。如果 MCP server 注册的工具与内置工具同名，启动会直接报错——不存在静默覆盖的情况。
