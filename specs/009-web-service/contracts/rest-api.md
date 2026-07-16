# Contract: REST API（10 端点）+ 统一信封

统一前缀 `/api/v1`。所有响应用 `ApiResponse`（`code`/`message`/`data`/`timestamp`）；错误全部经 `GlobalExceptionHandler` 翻译。Controller 只做校验/包装/兜错，逻辑委托核心服务。

## 信封

- 成功：`{ "code": 0, "message": "success", "data": <payload>, "timestamp": <ms> }`
- 错误：`{ "code": <httpStatus>, "message": <人可读原因>, "data": null, "timestamp": <ms> }`

## 端点

| # | 方法 · 路径 | 入 | 出(data) | 错误 |
|---|---|---|---|---|
| 1 | POST `/sessions` | CreateSessionRequest{profile,userId?} | {sessionId} | profile 缺失→400 |
| 2 | POST `/sessions/{id}/messages` | MessageRequest{content} | {reply} | content 空/超32KB→400；会话不存在→404；provider 故障→503；超时→504 |
| 3 | GET `/sessions/{id}` | — | SessionView（messages 尾≤100） | 不存在→404 |
| 4 | DELETE `/sessions/{id}` | — | {archived:true} | 不存在→404 |
| 5 | POST `/agents/{name}/invoke` | MessageRequest{content} | {reply} | content 空/超32KB→400；Agent 不存在→404；503/504 同上 |
| 6 | GET `/profiles` | — | ProfileView[] | — |
| 7 | GET `/memory` | — | 长期记忆全文 | — |
| 8 | GET `/tools` | — | ToolView[] | — |
| 9 | GET `/health` | — | {status:"ok"} | — |
| 10 | GET `/info` | — | InfoView{运行信息, providers[]} | — |

## 关键契约点

- **同一引擎**：#2 与 #5 MUST 调 `AgentService.process`，与 `oryxos chat` 同一入口；Controller 不夹带逻辑（切片测试 verify 恰一次）。
- **限制**：单条消息 ≤32KB（超→400，且不触发编排）；`/sessions/{id}` 历史返回 ≤100 条。
- **错误码**：400 参数错误 / 404 资源不存在 / 500 内部错误 / 503 Provider 故障 / 504 Agent 调用超时（60s 上限）。
- **500 不泄漏**：500 响应体 MUST NOT 含内部异常 message（连接串/堆栈）；只给统一话术，细节进日志（关键回归）。
- **CLI/REST 同源**：#3 能查到 CLI 里同一 session_id 的历史（共享存储）。

## 管理台（/admin，非 REST 契约，Vue SPA）

- Spring 同进程同端口托管 `/admin`；五页调 #3/#6/#7/#8/#10 的只读端点渲染。
- 只读：无任何写操作入口。错误显示 `ApiResponse.message`，三态（加载/空/错）齐。
- SPA：`/admin/**` 未命中回落 `admin/index.html`；`/api/v1/**` 路由不受影响。

## OpenAPI

`springdoc-openapi` 自动生成，暴露 `/swagger-ui`；不手写接口文档。

## 边界（本契约不含）

认证鉴权、SSE、WebSocket、限流、RBAC、Profile/Memory 写端点、`/api/v1/agents` CRUD（29/30 节）——本节 `AgentApiController` 只有 `/invoke` 一个端点。
