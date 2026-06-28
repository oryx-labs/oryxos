# REST API

OryxOS 在 `/api/v1` 前缀下提供 10 个 REST 端点。核心阶段不做认证——假设内网部署。

## 端点列表

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/v1/sessions` | 创建会话 |
| `POST` | `/api/v1/sessions/{id}/messages` | 发消息（触发 ReAct Loop） |
| `GET` | `/api/v1/sessions/{id}` | 查会话历史 |
| `DELETE` | `/api/v1/sessions/{id}` | 归档会话 |
| `POST` | `/api/v1/agents/{name}/invoke` | 无状态调用 Agent |
| `GET` | `/api/v1/profiles` | 列所有 Profile |
| `GET` | `/api/v1/memory` | 查长期记忆（MEMORY.md 内容） |
| `GET` | `/api/v1/tools` | 列可用 Tool |
| `GET` | `/api/v1/health` | 健康检查 |
| `GET` | `/api/v1/info` | 运行信息 + Provider 状态 |

## 示例：创建会话并发送消息

```bash
# 创建会话
curl -X POST http://localhost:8080/api/v1/sessions \
  -H "Content-Type: application/json" \
  -d '{"profile": "ops-agent", "channel": "api", "user_id": "user-001"}'

# 发送消息
curl -X POST http://localhost:8080/api/v1/sessions/sess-001/messages \
  -H "Content-Type: application/json" \
  -d '{"content": "查一下服务器当前的磁盘使用情况"}'
```

## 核心阶段不做

- 认证和 RBAC（假设内网）
- SSE 流式响应
- WebSocket 连接
- 限流
