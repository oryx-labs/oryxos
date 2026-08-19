# Contract: 知识库 REST API

**Date**: 2026-08-19　**Feature**: [spec.md](../spec.md)　**前缀**: `/api/v1`　**响应**: 统一 `ApiResponse` 包裹；错误走 `GlobalExceptionHandler`

## 1. 知识库全生命周期（`KnowledgeApiController`）

| 方法 | 路径 | 说明 | 关键语义 |
|---|---|---|---|
| `GET` | `/knowledge` | 列所有知识库 | `KnowledgeBaseInfo[]`：名称/描述/后端/文档数/片段数/索引状态/最近索引时间 |
| `POST` | `/knowledge` | 创建知识库 | body: name + description（+ 可选 backend）；重名 409；建目录 + 清单 |
| `GET` | `/knowledge/{name}` | 库详情 | 库信息 + 文档清单（`DocumentStatus[]`） |
| `PATCH` | `/knowledge/{name}` | 改描述 | 只写清单 frontmatter |
| `DELETE` | `/knowledge/{name}` | 删除库 | 被 Agent 引用 ⇒ 409 + 引用 Agent 清单（FR-011）；`?force=true` 不提供 |
| `POST` | `/knowledge/{name}/documents` | 上传文档（multipart） | **两段式**：同步落盘 + 解析校验（不支持类型/扫描件/超 10MB ⇒ 4xx 当场拒绝，含原因）；返回文档初始状态 `PENDING`；切分向量化后台推进 |
| `DELETE` | `/knowledge/{name}/documents/{relPath}` | 删单个文档 | 片段级联清理 |
| `GET` | `/knowledge/{name}/status` | 索引状态查询 | 文档状态机可随时查（待索引/索引中/就绪/失败+原因） |
| `POST` | `/knowledge/{name}/reindex` | 重建索引 | **双缓冲**：旧索引持续服务，就绪原子切换；失败旧索引不受影响（FR-024） |
| `GET` | `/knowledge/{name}/metrics` | 使用看板（FR-023） | query: `from`/`to` 时间过滤；返回检索次数、零结果率、降级率、命中文档分布、出处引用率、零结果查询原文列表；只聚合 `tool_invocations` |

**能力门禁（FR-006/SC-011）**：管理类端点（创建/删除/上传/重建）进入时按目标库后端的
`KnowledgeCapabilities` 判定，未声明能力 ⇒ `400`「该知识库后端不支持此操作」，
MUST NOT 半执行。`GET /knowledge` 与详情响应中携带后端能力集，管理台据此渲染
（不支持的操作不显示按钮，FR-009）。

## 2. Agent 绑定三件套（`AgentApiController`，照 skills 三件套先例）

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/agents/{name}/knowledge` | 列该 Agent 绑定（含链接合法性状态） |
| `PUT` | `/agents/{name}/knowledge/{kb}` | 绑定（建软连接；真实路径校验，非法 4xx） |
| `DELETE` | `/agents/{name}/knowledge/{kb}` | 解绑（删软连接） |

绑定变更即刻生效（下一轮对话上下文变化，SC-007 管理台视图 = CLI 输出 = 文件系统
事实）。Agent 新建/编辑表单与「一句话生成」草稿走既有 Agent 端点，请求/响应扩展
`knowledgeBindings: string[]` 字段（FR-018；起草上下文注入现有知识库名单）。

## 3. 错误语义汇总

| 场景 | 状态码 | 响应要点 |
|---|---|---|
| 重名建库 | 409 | 提示已存在 |
| 删除被引用库 | 409 | `data` 携带引用 Agent 名单 |
| 上传不支持类型 / 扫描件 PDF / 超 10MB | 400 | 当场拒绝 + 明确原因（SC-010） |
| 对仅检索后端调管理操作 | 400 | 「该知识库后端不支持此操作」 |
| 绑定链接非法（绝对/越界/名称不一致） | 400 | 非法类别可读描述 |
| 限定检索不存在/未绑定的库 | 工具层 | 可读错误文本（不 HTTP 化，发生在 ReAct 内） |
| 远程后端不可达 | 工具层 / 管理台状态列 | 检索返回可读错误并入审计；状态列「不可用」 |
