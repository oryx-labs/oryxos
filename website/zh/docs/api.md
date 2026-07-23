# REST API

OryxOS 在 `/api/v1` 前缀下提供 JSON REST API。核心阶段不做认证——假设内网部署，业务系统直接 HTTP 接入。所有请求体和响应体均为 JSON。

启动服务：

```bash
oryxos serve --port 8080
```

---

## 统一响应体

**每一个**端点都返回同一个信封，真正的载荷放在 `data` 里：

```json
{
  "code": 0,
  "message": "success",
  "data": { },
  "timestamp": 1720000000000
}
```

| 字段 | 类型 | 含义 |
|------|------|------|
| `code` | int | 成功为 `0`；出错为非零 |
| `message` | string | `"success"`，或错误描述 |
| `data` | any | 端点载荷（对象、数组、字符串或 `null`） |
| `timestamp` | long | 服务端 epoch 毫秒 |

出错时 `code` 非零、`message` 为可读错误信息、`data` 为 `null`：

```json
{
  "code": 400,
  "message": "创建会话缺少 profile",
  "data": null,
  "timestamp": 1720000000000
}
```

| HTTP 状态 | 触发 | 示例 |
|-----------|------|------|
| `400` | `IllegalArgumentException`——入参非法或缺失 | provider 名为空、路径越界、未知 sandbox 类别 |
| `404` | `ResourceNotFoundException`——资源不存在 | 未知会话 id、未知 provider 名 |

为简洁起见，下文示例只展示 `data` 载荷——记住它始终被包在上面的信封里。

---

## 系统

### 健康检查

**GET** `/api/v1/health`

轻量存活探针，服务正常时返回 `200 OK`。

```json
// data
{ "status": "ok" }
```

### 运行信息

**GET** `/api/v1/info`

返回应用名和已配置的 Provider 名单（"已配置"口径——核心阶段不做 live 探活）。

```json
// data
{
  "application": "oryxos",
  "providers": ["deepseek", "qwen"]
}
```

---

## Agent

一个 Agent 就是目录 `.oryxos/agents/<name>/`，其中 `AGENT.md` = frontmatter（这个 Agent 的 Profile）+ 任务正文。以下端点管理完整的动态生命周期——创建、查询、更新、删除、调用，以及查看 Agent 的记忆和控制台会话。

### 创建 Agent

**POST** `/api/v1/agents`

脚手架出 `.oryxos/agents/<name>/AGENT.md`，派生其 Profile 并注册。失败时回滚已创建的目录。

```json
// 请求
{
  "name": "ops-agent",
  "description": "运维助手"
}
```

```json
// data —— AgentView
{
  "name": "ops-agent",
  "description": "运维助手",
  "provider": "deepseek",
  "model": "deepseek-chat",
  "tools": ["read_file", "shell", "http_get"],
  "schedules": []
}
```

### 列出 Agent

**GET** `/api/v1/agents`

```json
// data —— AgentView[]
[
  {
    "name": "ops-agent",
    "description": "运维助手",
    "provider": "deepseek",
    "model": "deepseek-chat",
    "tools": ["read_file", "shell", "http_get"],
    "schedules": []
  }
]
```

### 查询单个 Agent

**GET** `/api/v1/agents/{name}`

```json
// data —— AgentView
{
  "name": "weather-daily",
  "description": "每天把天气和穿衣建议推送到飞书",
  "provider": "deepseek",
  "model": "deepseek-chat",
  "tools": ["http_get", "notify"],
  "schedules": [
    { "id": "morning", "cron": "0 0 8 * * *", "zone": "Asia/Shanghai", "message": "推送今天的天气和穿衣建议" }
  ]
}
```

### 更新 Agent

**PUT** `/api/v1/agents/{name}`

覆写整段 `AGENT.md` 文本。若 `schedules` 有变化，会先反注册再重新注册。

```json
// 请求
{
  "agentMarkdown": "---\nname: ops-agent\ndescription: 运维助手\n...\n---\n\n你是一个专业的运维助手。被触发时……"
}
```

```json
// data —— AgentView（重新派生后的视图）
{
  "name": "ops-agent",
  "description": "运维助手",
  "provider": "deepseek",
  "model": "deepseek-chat",
  "tools": ["read_file", "shell", "http_get"],
  "schedules": []
}
```

### 删除 Agent

**DELETE** `/api/v1/agents/{name}`

先取消调度，从注册表移除，再归档其目录（非物理删除）。

```json
// data
null
```

### 无状态调用

**POST** `/api/v1/agents/{name}/invoke`

不创建持久会话，执行一次单轮 ReAct Loop。

```json
// 请求
{ "content": "总结一下这个仓库最近 10 次提交。" }
```

```json
// data —— MessageResponse
{ "reply": "最近 10 次提交覆盖了 ReAct Loop、Provider 抽象和 SQLite 持久化。" }
```

### 查 Agent 记忆

**GET** `/api/v1/agents/{name}/memory`

返回这个 Agent 的 `MEMORY.md` 全文（`.oryxos/agents/<name>/MEMORY.md`，无 Agent 上下文时回退到全局 `.oryxos/memory/MEMORY.md`）。记忆按 Agent 隔离，每行带时间戳。

```json
// data —— MEMORY.md 文本
"2026-06-20 09:12:00 用户偏好使用 Markdown 格式输出结果。\n2026-06-25 14:03:11 线上数据库为 PostgreSQL 15。\n"
```

### 查控制台会话

**GET** `/api/v1/agents/{name}/session`

返回这个 Agent 的控制台会话（`admin:console:<agent>`）——即 Web 管理台"立即触发 / chat"所用的会话。

```json
// data —— SessionView
{
  "sessionId": "admin:console:ops-agent",
  "profileName": "ops-agent",
  "messages": [
    { "role": "user", "content": "查一下磁盘使用情况", "toolName": null, "toolCallId": null, "toolCalls": [] },
    { "role": "assistant", "content": "当前磁盘使用率 42%。", "toolName": null, "toolCallId": null, "toolCalls": [] }
  ]
}
```

### 向控制台会话发消息

**POST** `/api/v1/agents/{name}/session/messages`

向控制台会话发送一条消息并执行一次完整 ReAct Loop（管理台"立即触发 / chat"调的就是它）。

```json
// 请求
{ "content": "现在磁盘使用情况怎么样？" }
```

```json
// data —— MessageResponse
{ "reply": "当前磁盘使用率 42%，各挂载点均低于 50%。" }
```

### 一句话生成草稿文件

**POST** `/api/v1/agents/{name}/generate-files`

把一句话需求交给大模型，生成 `AGENT.md` 草稿。结果**仅预览**——既不落盘也不注册。

```json
// 请求
{ "description": "每天早上把 GitHub trending 推送到飞书" }
```

```json
// data —— GeneratedFilesView（{ 相对路径 -> 内容 }）
{
  "files": {
    "AGENT.md": "---\nname: github-daily\ndescription: ...\n---\n\n你是……"
  }
}
```

### 保存编辑后的文件

**POST** `/api/v1/agents/{name}/files`

保存（可能被用户改过的）一组 Agent 文件。保存即刻生效（写入文件、重新派生 Profile、注册）。

```json
// 请求
{
  "files": {
    "AGENT.md": "---\nname: github-daily\n...\n---\n\n...",
    "scripts/github_trending.py": "import urllib.request\n..."
  }
}
```

```json
// data —— AgentView
{
  "name": "github-daily",
  "description": "每天把 GitHub trending 推送到飞书",
  "provider": "deepseek",
  "model": "deepseek-chat",
  "tools": ["shell", "notify"],
  "schedules": []
}
```

---

## Provider

Provider **动态管理**，持久化在 SQLite（`providers` 表）。运行时按 provider 名从注册表解析 `ChatModel`，并按 `(name | apiKey | baseUrl)` 缓存，因此改 key 或 URL 会在下次调用时重建模型。名字 `mock` 是内置的假模型，不需要 key 或 URL。`application.yml` 里的 `oryxos.providers[]` 仅在表中缺失时于启动时**播种**进去——此后以数据库为准。

> 按用户选择，`apiKey` 以**明文**回显（核心阶段内网部署）。

### 创建 Provider

**POST** `/api/v1/providers`

`name` 全局唯一；非 `mock` 的 provider 需要 `baseUrl`（OpenAI 兼容端点）。重名或空名返回 `400`。

```json
// 请求 —— CreateProviderRequest
{
  "name": "deepseek",
  "apiKey": "sk-xxxxxxxx",
  "baseUrl": "https://api.deepseek.com",
  "description": "DeepSeek chat"
}
```

```json
// data —— ProviderView
{
  "name": "deepseek",
  "apiKey": "sk-xxxxxxxx",
  "baseUrl": "https://api.deepseek.com",
  "description": "DeepSeek chat"
}
```

### 列出 Provider

**GET** `/api/v1/providers`

```json
// data —— ProviderView[]
[
  { "name": "deepseek", "apiKey": "sk-xxxxxxxx", "baseUrl": "https://api.deepseek.com", "description": "DeepSeek chat" },
  { "name": "mock",     "apiKey": null,          "baseUrl": null,                       "description": "内置假模型" }
]
```

### 查询单个 Provider

**GET** `/api/v1/providers/{name}`

不存在返回 `404`。

```json
// data —— ProviderView
{ "name": "deepseek", "apiKey": "sk-xxxxxxxx", "baseUrl": "https://api.deepseek.com", "description": "DeepSeek chat" }
```

### 更新 Provider

**PUT** `/api/v1/providers/{name}`

name 在路径上，这里改 key / URL / 描述。

```json
// 请求 —— UpdateProviderRequest
{ "apiKey": "sk-yyyyyyyy", "baseUrl": "https://api.deepseek.com", "description": "已轮换的 key" }
```

```json
// data —— ProviderView
{ "name": "deepseek", "apiKey": "sk-yyyyyyyy", "baseUrl": "https://api.deepseek.com", "description": "已轮换的 key" }
```

### 删除 Provider

**DELETE** `/api/v1/providers/{name}`

```json
// data
null
```

---

## 通知渠道

通知渠道动态管理，持久化在 SQLite（`notify_channels` 表）。`notify` 工具在 `AGENT.md` 正文里以自然语言**按名字**引用渠道（例如"发到 team-lark"），工具据此解析出已注册渠道的适配器和 URL。AGENT.md frontmatter 里没有 `notify_channels` 字段。

`type` 取 `feishu` | `wecom` | `dingtalk` | `webhook`（各由一个适配器实现）。

### 创建通知渠道

**POST** `/api/v1/notify-channels`

```json
// 请求 —— CreateNotifyChannelRequest
{
  "name": "team-lark",
  "type": "feishu",
  "url": "https://open.larksuite.com/open-apis/bot/v2/hook/xxxx",
  "description": "团队飞书群"
}
```

```json
// data —— NotifyChannelView
{
  "name": "team-lark",
  "type": "feishu",
  "url": "https://open.larksuite.com/open-apis/bot/v2/hook/xxxx",
  "description": "团队飞书群"
}
```

### 列出通知渠道

**GET** `/api/v1/notify-channels`

```json
// data —— NotifyChannelView[]
[
  { "name": "team-lark", "type": "feishu", "url": "https://open.larksuite.com/open-apis/bot/v2/hook/xxxx", "description": "团队飞书群" }
]
```

### 查询单个通知渠道

**GET** `/api/v1/notify-channels/{name}`

```json
// data —— NotifyChannelView
{ "name": "team-lark", "type": "feishu", "url": "https://open.larksuite.com/open-apis/bot/v2/hook/xxxx", "description": "团队飞书群" }
```

### 更新通知渠道

**PUT** `/api/v1/notify-channels/{name}`

```json
// 请求 —— UpdateNotifyChannelRequest
{ "type": "webhook", "url": "https://example.com/hook", "description": "通用 webhook" }
```

### 删除通知渠道

**DELETE** `/api/v1/notify-channels/{name}`

```json
// data
null
```

---

## 会话

会话（Session）是用户与 Agent 之间的有状态多轮对话，携带对话历史、绑定一个 Profile，历史持久化在 SQLite 中。

### 创建会话

**POST** `/api/v1/sessions`

`profile` 必填（为空返回 `400`）；`userId` 缺省为 `default`。API 创建的会话渠道固定为 web 渠道。

```json
// 请求 —— CreateSessionRequest
{ "profile": "ops-agent", "userId": "user-001" }
```

```json
// data
{ "sessionId": "web:user-001:ops-agent" }
```

### 发消息

**POST** `/api/v1/sessions/{id}/messages`

把用户消息追加到会话历史并触发 ReAct Loop，阻塞至 Agent 产出最终响应（同步）。未知会话 id 返回 `404`；内容为空返回 `400`。

```json
// 请求 —— MessageRequest
{ "content": "查一下服务器当前的磁盘使用情况" }
```

```json
// data —— MessageResponse
{ "reply": "当前磁盘使用情况：/dev/sda1 使用率 42%，其余挂载点均低于 30%。" }
```

### 列出会话

**GET** `/api/v1/sessions?status=active`

返回最近的会话摘要（最多 100 条，按活跃倒序）。可选 `status` 查询参数按状态过滤。

```json
// data —— SessionSummaryView[]
[
  {
    "sessionId": "web:user-001:ops-agent",
    "profileName": "ops-agent",
    "channel": "web",
    "userId": "user-001",
    "status": "active",
    "createdAt": "2026-06-28T10:00:00Z",
    "lastActiveAt": "2026-06-28T10:01:02Z",
    "messageCount": 2
  }
]
```

### 查会话历史

**GET** `/api/v1/sessions/{id}`

返回最近 ≤100 条消息。未知会话 id 返回 `404`。

```json
// data —— SessionView
{
  "sessionId": "web:user-001:ops-agent",
  "profileName": "ops-agent",
  "messages": [
    { "role": "user",      "content": "查一下磁盘使用情况", "toolName": null, "toolCallId": null, "toolCalls": [] },
    { "role": "assistant", "content": "当前磁盘使用率 42%。", "toolName": null, "toolCallId": null, "toolCalls": [] }
  ]
}
```

### 归档会话

**DELETE** `/api/v1/sessions/{id}`

标记会话为已归档，数据保留在 SQLite，不再出现在活跃列表中。未知会话 id 返回 `404`。

```json
// data
{ "archived": true }
```

---

## 定时任务

定时任务从每个 Agent 的 `AGENT.md` 里的 `schedules` 块派生。Web 管理台可以列出、查看、手动触发、启停它们。

### 列出定时任务

**GET** `/api/v1/schedules`

```json
// data —— ScheduleView[]
[
  {
    "taskId": "weather-daily:morning",
    "profileName": "weather-daily",
    "cron": "0 0 8 * * *",
    "zone": "Asia/Shanghai",
    "message": "推送今天的天气和穿衣建议",
    "enabled": true,
    "nextRunAt": "2026-07-23T00:00:00Z",
    "lastRunAt": "2026-07-22T00:00:00Z",
    "lastStatus": "success",
    "runCount": 12
  }
]
```

### 列出执行历史

**GET** `/api/v1/schedules/{id}/executions?limit=20`

返回某个任务的执行历史（`limit` 限制条数）。

```json
// data —— ExecutionView[]
[
  {
    "taskId": "weather-daily:morning",
    "sessionId": "schedule:weather-daily:morning",
    "startedAt": "2026-07-22T00:00:00Z",
    "success": true,
    "errorMessage": null,
    "durationMs": 1842
  }
]
```

### 立即触发

**POST** `/api/v1/schedules/{id}/run`

立即触发任务并返回本次执行记录。

```json
// data —— ExecutionView[]
[
  { "taskId": "weather-daily:morning", "sessionId": "schedule:weather-daily:morning", "startedAt": "2026-07-22T09:30:00Z", "success": true, "errorMessage": null, "durationMs": 1730 }
]
```

### 启用 / 停用

**PUT** `/api/v1/schedules/{id}`

```json
// 请求 —— SetEnabledRequest
{ "enabled": false }
```

```json
// data —— ScheduleView[]（更新后的任务列表）
[
  { "taskId": "weather-daily:morning", "profileName": "weather-daily", "cron": "0 0 8 * * *", "zone": "Asia/Shanghai", "message": "推送今天的天气和穿衣建议", "enabled": false, "nextRunAt": null, "lastRunAt": "2026-07-22T00:00:00Z", "lastStatus": "success", "runCount": 12 }
]
```

---

## Profile

### 列所有 Profile

**GET** `/api/v1/profiles`

返回派生出的 Profile——`.oryxos/agents/` 下每个 Agent 目录一个。

```json
// data —— ProfileView[]
[
  {
    "name": "ops-agent",
    "description": "运维助手",
    "provider": "deepseek",
    "model": "deepseek-chat",
    "tools": ["read_file", "shell", "http_get", "save_memory", "recall_memory"]
  }
]
```

---

## 工具

### 列可用 Tool

**GET** `/api/v1/tools`

列出 `ToolRegistry` 中注册的所有 Tool——内置 Tool 加上已配置 MCP server 暴露的 Tool。

```json
// data —— ToolView[]
[
  { "name": "read_file", "description": "读取文件内容，路径受白名单限制" },
  { "name": "shell",     "description": "执行 shell 命令，命令首 token 受白名单限制" },
  { "name": "notify",    "description": "按名字把消息推送到已注册的通知渠道" }
]
```

---

## Sandbox 白名单

Sandbox 白名单分三类——`FILE`（允许路径）、`SHELL`（允许命令 token）、`HTTP`（允许域名）——可在运行时调整，改动在下一次工具调用生效。`{category}` 路径变量大小写不敏感（`file` / `shell` / `http`）；未知类别返回 `400`。

### 查询白名单

**GET** `/api/v1/sandbox/whitelist`

一次返回三类全部条目。

```json
// data
{
  "file": ["/home/user/project/.oryxos"],
  "shell": ["ls", "cat", "python3"],
  "http": ["*.open-meteo.com", "hn.algolia.com"]
}
```

### 新增条目

**POST** `/api/v1/sandbox/whitelist/{category}`

向某类加一条（幂等），返回是否变更及该类最新全量。

```json
// 请求
{ "value": "*.example.com" }
```

```json
// data —— WhitelistChange
{
  "category": "http",
  "value": "*.example.com",
  "changed": true,
  "entries": ["*.open-meteo.com", "hn.algolia.com", "*.example.com"]
}
```

### 删除条目

**DELETE** `/api/v1/sandbox/whitelist/{category}?value=*.example.com`

`value` 是**查询参数**；为空返回 `400`。

```json
// data —— WhitelistChange
{
  "category": "http",
  "value": "*.example.com",
  "changed": true,
  "entries": ["*.open-meteo.com", "hn.algolia.com"]
}
```

---

## 工作区文件浏览

工作区（`agents/` 与 `archive/`）的只读目录树，加上按文件读 / 写。所有路径相对工作区根目录；越界路径（路径穿越）返回 `400`。

### 目录树

**GET** `/api/v1/workspace/tree`

根节点以工作区目录命名。

```json
// data —— FileNode
{
  "name": ".oryxos",
  "path": "",
  "type": "dir",
  "children": [
    {
      "name": "agents",
      "path": "agents",
      "type": "dir",
      "children": [
        { "name": "AGENT.md", "path": "agents/ops-agent/AGENT.md", "type": "file", "children": [] }
      ]
    }
  ]
}
```

### 读文件

**GET** `/api/v1/workspace/file?path=agents/ops-agent/AGENT.md`

以字符串返回文件内容。越界路径返回 `400`。

```json
// data —— 文件文本
"---\nname: ops-agent\ndescription: 运维助手\n---\n\n你是一个专业的运维助手……"
```

### 写文件

**POST** `/api/v1/workspace/file`

与读文件相同的路径守卫。

```json
// 请求 —— WriteFileRequest
{
  "path": "agents/ops-agent/AGENT.md",
  "content": "---\nname: ops-agent\n...\n---\n\n..."
}
```

```json
// data
null
```

---

## 核心阶段不做

以下能力留待后续阶段：

- 认证和 RBAC（核心阶段假设内网）
- SSE 流式响应
- WebSocket 连接
- 限流
