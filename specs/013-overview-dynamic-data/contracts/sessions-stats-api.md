# API Contract: GET /api/v1/sessions/stats

## Endpoint

```
GET /api/v1/sessions/stats
```

## Description

返回会话按状态的计数统计：活跃、归档、总计。供管理台概览页 "活跃会话" 统计卡使用。

## Request

No request body. No query parameters.

## Response

### Success (200 OK)

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "active": 5,
    "archived": 12,
    "total": 17
  },
  "timestamp": 1753862400000
}
```

- `active`: 状态为 `"active"` 的会话数
- `archived`: 状态为 `"archived"` 的会话数
- `total`: 所有会话数（`active + archived`）

### Error Cases

| 场景 | HTTP Status | `code` | 说明 |
|------|------------|--------|------|
| 数据库不可用 | 500 | 非 0 | `message` 字段含错误描述 |

## Implementation Notes

- 控制器方法: `SessionApiController.stats()` — 新增 `@GetMapping("/stats")`
- DTO: `SessionStatsView` — 新增 `record`，位于 `io.oryxos.web.controller.dto`
- 核心接口: `SessionManager.stats()` — 返回 `SessionStats` record（`oryxos-core`）
- 存储实现: `JpaSessionManager.stats()` — 三次 `repository.countByStatus(...)` 调用
- JPA 查询: `SessionRepository.countByStatus(String status)` — Spring Data 派生查询，自动生成 `SELECT COUNT(*) FROM sessions WHERE status = ?`

## Usage (Frontend)

```javascript
fetch('/api/v1/sessions/stats')
  .then(res => res.json())
  .then(body => {
    if (body.code === 0) {
      overview.stats.sessions.value = body.data.active
      overview.stats.sessions.loading = false
    }
  })
```
