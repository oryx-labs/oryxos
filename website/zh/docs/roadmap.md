# 路线图

OryxOS 分四周核心阶段实施，之后进入扩展阶段。

## 四周实施节奏

| 周次 | 核心任务 | 涉及模块 | 验收 Demo |
|------|---------|---------|----------|
| 第一周 | Provider 抽象 + ReAct Loop | `oryxos-core` `oryxos-provider` `oryxos-channel-cli` `oryxos-cli` | `oryxos chat`：查天气穿衣 |
| 第二周 | Memory + Tool 体系 | `oryxos-memory` `oryxos-tool` | Agent 跨对话记偏好；零代码 PR digest |
| 第三周 | Web Service | `oryxos-web` `oryxos-storage` | 10 个 REST 端点完整调用 |
| 第四周 | 多 Agent 演示 + 工程化 | 所有模块收尾 | 多 Agent 并存；Session 跨重启恢复 |

## 规划中的扩展功能

- **SSE 流式** — 对话响应实时 token 流
- **情景记忆** — 带语义检索的结构化事件日志
- **RBAC** — REST API 的基于角色的访问控制
- **WebSocket Channel** — 持久化双向 Agent 连接
- **限流** — 按用户和 Provider 的请求节流
- **Agent 间消息传递** — 直接的 Agent 到 Agent 任务委托
- **工作流引擎** — 多步骤声明式 Agent 工作流
- **Web UI** — 浏览器端对话界面和管理面板
