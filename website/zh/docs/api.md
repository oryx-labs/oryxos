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
{ "reply": "最近 10 次提交覆盖了 ReAct Loop、Provider 抽象和 SQLite 持久化。",
  "traceId": "3f9c2b1a-…" }
```

`traceId`（021）标识这一次消息处理的全链路，用于报障定位与审计回放（见「审计 Trace」节）。

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

> Provider key 落库加密，Provider API 响应中的 `apiKey` 只返回掩码值，不会回显存储的凭证。

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
  "apiKey": "****xxxx",
  "baseUrl": "https://api.deepseek.com",
  "description": "DeepSeek chat"
}
```

### 列出 Provider

**GET** `/api/v1/providers`

```json
// data —— ProviderView[]
[
  { "name": "deepseek", "apiKey": "****xxxx", "baseUrl": "https://api.deepseek.com", "description": "DeepSeek chat" },
  { "name": "mock",     "apiKey": null,          "baseUrl": null,                       "description": "内置假模型" }
]
```

### 查询单个 Provider

**GET** `/api/v1/providers/{name}`

不存在返回 `404`。

```json
// data —— ProviderView
{ "name": "deepseek", "apiKey": "****xxxx", "baseUrl": "https://api.deepseek.com", "description": "DeepSeek chat" }
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
{ "name": "deepseek", "apiKey": "****yyyy", "baseUrl": "https://api.deepseek.com", "description": "已轮换的 key" }
```

### 删除 Provider

**DELETE** `/api/v1/providers/{name}`

```json
// data
null
```

---

## 通知渠道

通知渠道动态管理，持久化在 SQLite（`notify_channels` 表）。config 中密码类敏感项（`password/secret/token/api_key` 等名录）与渠道 `url`（`notify_channels.url`——webhook URL 本身就是凭证；email 渠道的描述性 URL 仍为明文）落库加密（022，`enc:v1:` 前缀）；查询接口对这些项只回显掩码（`****`+末 4 位），编辑时掩码原样提交或留空 = 保持原值（与 Provider 的 api-key 交互范式一致）。config 中密码类敏感项（`password/secret/token/api_key` 等名录）与渠道 `url`（`notify_channels.url`——webhook URL 本身就是凭证；email 渠道的描述性 URL 仍为明文）落库加密（022，`enc:v1:` 前缀）；查询接口对这些项只回显掩码（`****`+末 4 位），编辑时掩码原样提交或留空 = 保持原值（与 Provider 的 api-key 交互范式一致）。`notify` 工具在 `AGENT.md` 正文里以自然语言**按名字**引用渠道（例如"发到 team-lark"），工具据此解析出已注册渠道的适配器和 URL。AGENT.md frontmatter 里没有 `notify_channels` 字段。

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

### SSE 流式响应

三个消息端点（本端点、[无状态调用](#无状态调用)、[向控制台会话发消息](#向控制台会话发消息)）支持流式：请求头带 `Accept: text/event-stream` 即改为 SSE 事件流,不带则维持上述一次性 JSON。

```bash
curl -N -H "Accept: text/event-stream" -H "Content-Type: application/json" \
  -d '{"content":"介绍一下你自己"}' \
  http://localhost:8080/api/v1/sessions/<id>/messages
```

事件类型（`data:` 为单行 JSON）:

| event | data 负载 | 说明 |
| --- | --- | --- |
| `token` | `{"delta":"…"}` | 回复文本增量（打字机） |
| `tool_start` | `{"name":"shell"}` | 工具调用开始 |
| `tool_end` | `{"name":"shell","success":true}` | 工具调用结束 |
| `done` | `{"reply":"完整回复"}` | 终结事件（与 error 二选一,恰好一个） |
| `error` | `{"code":500,"message":"…"}` | 流中失败的终结事件 |
| `: ping` | —（SSE 注释行） | 心跳,间隔由 `oryxos.web.sse.heartbeat-seconds` 控制（默认 15s）,解析器应忽略 |

语义承诺:`token` 按序拼接等于 `done.reply`;流开始前的失败（404/401/400）仍返 JSON 状态码;客户端中途断开后服务端照常完成本轮并落库、审计照写（断开不退款）;流式与非流式的 `llm_calls`/`tool_invocations` 审计口径完全一致。完整契约见仓库 `specs/019-sse-streaming/contracts/sse-protocol.md`。

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

新客户端必须使用 v2：`scheduleId` 是全局稳定的运行态身份，`key` 只在单个 Agent 内唯一。

### v2 运行态 API

- **GET** `/api/v2/schedules`
- **POST** `/api/v2/schedules/{scheduleId}/run`
- **PUT** `/api/v2/schedules/{scheduleId}`
- **GET** `/api/v2/schedules/{scheduleId}/executions?limit=20`
- **POST** `/api/v2/agents/{profileName}/schedules/{key}/run`

任务列表返回 `scheduleId`、`profileName`、`key`、`name` 和运行态字段。执行历史返回 `scheduleId`；由旧库迁入的记录会带 `legacyMigrated: true` 与 `legacyTaskKey`。

### 已弃用的 v1 兼容 API

下面的 v1 路径参数仍按旧配置 `key` 解析，只在全库唯一匹配时兼容；多个 Agent 使用相同 key 时一律返回 HTTP 409，不会静默选择其中一条。

**GET** `/api/v1/schedules`

```json
// data —— LegacyScheduleView[]
[
  {
    "taskId": "morning",
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
    "scheduleId": "ae3d1f7b-245c-4c37-bbf7-38a6db55bfce",
    "legacyTaskKey": null,
    "legacyMigrated": false,
    "sessionId": "schedule:ae3d1f7b-245c-4c37-bbf7-38a6db55bfce",
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
  { "scheduleId": "ae3d1f7b-245c-4c37-bbf7-38a6db55bfce", "legacyTaskKey": null, "legacyMigrated": false, "sessionId": "schedule:ae3d1f7b-245c-4c37-bbf7-38a6db55bfce", "startedAt": "2026-07-22T09:30:00Z", "success": true, "errorMessage": null, "durationMs": 1730 }
]
```

### 启用 / 停用

**PUT** `/api/v1/schedules/{id}`

```json
// 请求 —— SetEnabledRequest
{ "enabled": false }
```

```json
// data —— LegacyScheduleView[]（更新后的任务列表）
[
  { "taskId": "morning", "profileName": "weather-daily", "cron": "0 0 8 * * *", "zone": "Asia/Shanghai", "message": "推送今天的天气和穿衣建议", "enabled": false, "nextRunAt": null, "lastRunAt": "2026-07-22T00:00:00Z", "lastStatus": "success", "runCount": 12 }
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
  { "name": "shell",     "description": "执行 shell 命令，受命令白名单限制（argv 直传，无 Shell 解释）" },
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
  "shell": ["ls", "cat", "echo", "grep"],
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

## 工具策略

平台管理员的治理层（020）:全局 + Agent 级工具 allow/deny,与 `AGENT.md` 的 `tools:`（作者自选集）分离。策略只做减法——有效工具集恒 ⊆ 声明集;与沙箱白名单正交（策略管"能不能用这个工具",沙箱管"工具能碰什么资源"）。零规则 = 现状零破坏;规则增删即刻热更新生效。

规则三类:`GLOBAL_DENY`（全局禁用,不带 agentName）、`AGENT_EXEMPT`（指定 Agent 豁免全局禁用）、`AGENT_DENY`（指定 Agent 定向收紧,例外救不回）。`pattern` 为工具精确名或 MCP server 通配（`github-mcp:*`,按工具注册归属判定）。三道保险:被禁工具不进模型清单（事前）、执行层按最新策略拒绝（事中,防幻觉调用）、`tool_invocations.blocked_by='policy'` 留痕（事后可筛）。

### 查询策略与有效工具集

**GET** `/api/v1/tool-policy`

```json
// data —— rules + 每个 Agent 的有效工具集（removed 附命中规则原因）
{
  "rules": [ { "id": 1, "ruleType": "GLOBAL_DENY", "agentName": null, "pattern": "shell",
               "createdAt": "…", "createdBy": "admin", "unknownTarget": false } ],
  "effective": [ { "agentName": "kb-tester", "declared": ["shell", "read_file"],
                   "effective": ["read_file"],
                   "removed": [ { "toolName": "shell", "reason": "命中全局禁用规则（#1 shell）" } ] } ]
}
```

### 新增规则

**POST** `/api/v1/tool-policy/rules` — `{ "ruleType": "AGENT_EXEMPT", "agentName": "ops-agent", "pattern": "shell" }`。`GLOBAL_DENY` 不得带 `agentName`,另两类必须带（否则 `400`）;重复规则 `409`;未知工具名保存成功但返回 `unknownTarget: true` 告警标记。

### 删除规则

**DELETE** `/api/v1/tool-policy/rules/{id}` — 不存在 `404`;删除即刻生效。

### 审计筛选

**GET** `/api/v1/audit/tool?blockedBy=policy` — 只看被策略拒绝的调用记录。

---

## 审计 Trace

一次消息处理（会话消息 / 无状态调用 / 定时触发 / 飞书入站）= 一个 trace ID（UUID），贯穿本轮全部审计记录、结构化日志（MDC `traceId` 字段）与回传通道——同一个值，凭它可在审计与日志间互查。默认开启零配置；升级前的旧审计行 trace 为空，既有查询不受影响。

回传三通道（021，全部纯增量）:

| 通道 | 形态 |
|------|------|
| REST 非流式 | `MessageResponse` 的 `traceId` 字段 |
| SSE 流式 | 流建立后首个业务事件 `event: trace`（`data: {"traceId":"…"}`）；`done` 负载同带 `traceId` |
| 执行历史 | `AgentExecutionView` 的 `traceId` 字段（`GET /agents/{name}/executions`） |

### 单轮全链路时间线

**GET** `/api/v1/audit/trace/{traceId}`

```json
// data —— TraceTimelineView
{ "traceId": "3f9c2b1a-…", "found": true,
  "steps": [
    { "seq": 1, "type": "LLM",  "name": "glm-4-flash", "success": true, "durationMs": 1200,
      "at": "…", "promptTokens": 812, "completionTokens": 64, "totalTokens": 876, "costMicros": 120 },
    { "seq": 2, "type": "TOOL", "name": "save_memory", "success": true, "durationMs": 15,
      "at": "…", "inputSummary": "{\"content\":\"…\"}", "resultSummary": "OK", "blockedBy": null },
    { "seq": 3, "type": "LLM",  "name": "glm-4-flash", "success": true, "durationMs": 900, "at": "…", "totalTokens": 540 }
  ],
  "summary": { "steps": 3, "llmCalls": 2, "toolCalls": 1,
               "totalTokens": 1416, "costMicros": 260, "totalDurationMs": 2115 } }
```

步骤按发生时间排序，失败与被策略拦截（`blockedBy: "policy"`）的步骤同样入链；未命中返回 `found: false` 空 `steps`（HTTP 200 不报错）。既有列表 `GET /audit/llm|tool` 的行视图增 `traceId` 字段（trace 维度入口）。管理台报表页提供 trace 查询框与时间线视图，明细行 traceId 可点查。

### 展示层脱敏

时间线里 TOOL 步的 `inputSummary`/`resultSummary`/`errorMessage` 为**截断（200 字符）+ 脱敏**后的展示值:API key 已知前缀（`sk-…`/`oryx_…`）、`Authorization` 凭证、URL 账密段、`password/secret/token/api_key` 类字段值 → `前4字符+****` 掩码。落库保持原文（排障现场完整，库访问=运维特权边界），规则内置不可配置；不含敏感形态的内容原样展示。

---

## Provider 失败切换与业务指标

**失败切换（023）**：`AGENT.md` 的 provider 节可声明有序备用列表——单次 LLM 调用发生 provider 侧故障（网络/超时/5xx/429/401/403）时按序切换备用重发同一请求，候选耗尽才以最后错误上抛；业务性失败（400 类）不切换。切换语义限定在「单次调用」层面（ReAct 轮次口径不变，每次调用独立从主开始）；流式调用仅在首个内容片段送出前可切换。每次尝试各写一条 `llm_calls`（主备留痕、trace 同链可回放），切换记 WARN 日志（from→to，带 traceId）。零声明 = 现状行为零变化。

```yaml
provider:
  name: deepseek
  model: deepseek-chat
  fallback:
    - name: qwen
      model: qwen-plus
```

**业务指标（023）**：`GET /actuator/prometheus`（既有端点）新增 `oryxos_` 前缀业务指标，供企业监控栈聚合与告警——`oryxos_llm_calls_total{provider,model,outcome}`（与 llm_calls 行数同口径）、`oryxos_llm_call_duration_seconds`、`oryxos_llm_tokens_total{type}`、`oryxos_tool_invocations_total{tool,outcome}`、`oryxos_policy_blocks_total{tool}`、`oryxos_fallback_switches_total{from,to}`。指标供监控聚合、审计供精确回放（正交，指标不改变审计落库口径）；未发生对应事件时指标缺席或为零均为正常形态。

---

## 工作区文件浏览

工作区的只读目录树，加上按文件读 / 写。目录树列出 `agents/`、`skills/`、`output/`、`archive/`（自定义 `readable-roots` 时目录树跟随该配置；默认配置下仍是这四个）；读只允许在配置的可读根下（默认就是这四个），写只允许在配置的可写根下（默认 `agents/`、`output/`、`archive/`；`skills/` 是只读视图）。所有路径相对工作区根目录；越界路径（路径穿越）、以及根内但不在允许根内的路径返回 `400`。两组根都可用 `oryxos.web.workspace.readable-roots` / `writable-roots` 覆盖（逗号分隔的单段目录名，只允许字母/数字/`_`/`-`；非法值拒绝启动，非默认值启动时打一条 WARN）。

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

以字符串返回文件内容。越界路径、以及根内但不在可读根内的路径返回 `400`。

```json
// data —— 文件文本
"---\nname: ops-agent\ndescription: 运维助手\n---\n\n你是一个专业的运维助手……"
```

### 写文件

**POST** `/api/v1/workspace/file`

与读文件相同的路径守卫，按可写根判定（默认 `agents/`、`output/`、`archive/`；`skills/` 是只读视图）。

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
