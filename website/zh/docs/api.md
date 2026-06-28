# REST API

OryxOS 在 `/api/v1` 前缀下提供 10 个 REST 端点。核心阶段不做认证——假设内网部署，业务系统直接 HTTP 接入。

## 端点总览

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/v1/sessions` | 创建会话 |
| `POST` | `/api/v1/sessions/{id}/messages` | 发消息（触发 ReAct Loop） |
| `GET` | `/api/v1/sessions/{id}` | 查会话历史 |
| `DELETE` | `/api/v1/sessions/{id}` | 归档会话 |
| `POST` | `/api/v1/agents/{name}/invoke` | 无状态调用 Agent |
| `GET` | `/api/v1/profiles` | 列所有 Profile |
| `GET` | `/api/v1/memory` | 查长期记忆（MEMORY.md） |
| `GET` | `/api/v1/tools` | 列可用 Tool |
| `GET` | `/api/v1/health` | 健康检查 |
| `GET` | `/api/v1/info` | 运行信息 + Provider 状态 |

## 会话管理

会话（Session）是 OryxOS 管理多轮对话状态的单元。一个会话绑定一个 Profile、一个渠道和一个用户 ID，对话历史持久化在 SQLite 中。

### 创建会话

`POST /api/v1/sessions`

**请求体：**

```json
{
  "profile": "ops-agent",
  "channel": "api",
  "user_id": "user-001"
}
```

**响应：**

```json
{
  "session_id": "sess-a1b2c3d4",
  "profile": "ops-agent",
  "channel": "api",
  "user_id": "user-001",
  "status": "active",
  "created_at": "2026-06-28T10:00:00Z"
}
```

---

### 发消息

`POST /api/v1/sessions/{id}/messages`

向会话发送一条消息，触发 ReAct Loop，等待 Agent 完整响应后返回。

**请求体：**

```json
{
  "content": "查一下服务器当前的磁盘使用情况"
}
```

**响应：**

```json
{
  "session_id": "sess-a1b2c3d4",
  "role": "assistant",
  "content": "当前磁盘使用情况如下：\n- /dev/sda1：已用 45G，可用 155G，使用率 22%\n- /dev/sdb1：已用 380G，可用 20G，使用率 95%\n\n/dev/sdb1 使用率较高，建议清理或扩容。",
  "tool_calls": 1,
  "iterations": 2,
  "duration_ms": 1842
}
```

---

### 查会话历史

`GET /api/v1/sessions/{id}`

**响应：**

```json
{
  "session_id": "sess-a1b2c3d4",
  "profile": "ops-agent",
  "status": "active",
  "messages": [
    {"role": "user", "content": "查一下服务器当前的磁盘使用情况", "created_at": "2026-06-28T10:01:00Z"},
    {"role": "assistant", "content": "当前磁盘使用情况如下：...", "created_at": "2026-06-28T10:01:02Z"}
  ],
  "created_at": "2026-06-28T10:00:00Z",
  "last_active_at": "2026-06-28T10:01:02Z"
}
```

---

### 归档会话

`DELETE /api/v1/sessions/{id}`

归档后会话状态变为 `archived`，历史记录保留，不再接受新消息。

**响应：**

```json
{
  "session_id": "sess-a1b2c3d4",
  "status": "archived",
  "archived_at": "2026-06-28T11:00:00Z"
}
```

## Agent 调用

### 无状态调用

`POST /api/v1/agents/{name}/invoke`

不创建持久会话，适合单次任务场景（批处理、Webhook 回调等）。`{name}` 对应 `.oryxos/profiles/` 下的 Profile 名称。

**请求体：**

```json
{
  "content": "总结一下今天的 GitHub PR 活动",
  "user_id": "user-001"
}
```

**响应：**

```json
{
  "content": "今日共有 3 个 PR 合并，5 个 PR 待 review……",
  "tool_calls": 2,
  "iterations": 3,
  "duration_ms": 3201
}
```

## Profile 管理

### 列所有 Profile

`GET /api/v1/profiles`

列出 `.oryxos/profiles/` 下所有已加载的 Profile。

**响应：**

```json
{
  "profiles": [
    {
      "name": "ops-agent",
      "description": "运维助手",
      "provider": "deepseek",
      "model": "deepseek-chat",
      "tools": ["read_file", "shell", "http_get", "save_memory", "recall_memory"]
    },
    {
      "name": "default",
      "description": "默认助手",
      "provider": "qwen",
      "model": "qwen-max",
      "tools": ["read_file", "http_get"]
    }
  ]
}
```

## 记忆

### 查长期记忆

`GET /api/v1/memory`

查询长期记忆内容（`.oryxos/memory/MEMORY.md` 全文）。长期记忆由 Agent 通过 `save_memory` Tool 写入，每次对话自动注入 system prompt。

**查询参数：**

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `profile` | string | 可选，过滤指定 Profile 的记忆 |
| `keyword` | string | 可选，关键词检索 |

**响应：**

```json
{
  "profile": "ops-agent",
  "content": "## 2026-06-20\n用户偏好使用 Markdown 格式输出结果。\n\n## 2026-06-25\n线上数据库为 PostgreSQL 15，主从各一台……",
  "size_bytes": 1204
}
```

## 工具

### 列可用 Tool

`GET /api/v1/tools`

列出 ToolRegistry 中当前注册的所有可用 Tool。

**响应：**

```json
{
  "tools": [
    {
      "name": "read_file",
      "description": "读取文件内容，路径受白名单限制",
      "source": "builtin"
    },
    {
      "name": "shell",
      "description": "执行 shell 命令，命令首 token 受白名单限制",
      "source": "builtin"
    },
    {
      "name": "github_search_issues",
      "description": "搜索 GitHub Issues",
      "source": "mcp:github-mcp"
    }
  ]
}
```

## 系统

### 健康检查

`GET /api/v1/health`

服务正常时返回 `200 OK`，可用于负载均衡探针或监控告警。

**响应：**

```json
{
  "status": "ok"
}
```

### 运行信息

`GET /api/v1/info`

查询运行信息和所有已配置 Provider 的连通状态。

**响应：**

```json
{
  "version": "0.1.0",
  "workspace": "/home/app/.oryxos",
  "uptime_seconds": 3600,
  "providers": [
    {"name": "deepseek", "status": "ok", "model": "deepseek-chat"},
    {"name": "qwen", "status": "ok", "model": "qwen-max"}
  ],
  "active_sessions": 5,
  "tools_registered": 9
}
```

## 核心阶段不做

- 认证和 RBAC（假设内网）
- SSE 流式响应
- WebSocket 连接
- 限流
