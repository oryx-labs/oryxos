# Contract: Public Skill and Agent Association REST API

成功沿用 `ApiResponse<T>`：HTTP 200、`code=0`。失败使用对应 HTTP 状态与统一信封；`data` 可承载机器可读冲突信息。响应不得包含绝对路径、堆栈、包正文或密钥。

## 1. DTO

### PublicSkillSummaryView

```json
{
  "name": "weather",
  "description": "查询天气并给出出行建议",
  "status": "enabled",
  "configuredEnabled": true,
  "source": "upload",
  "updatedAt": "2026-07-24T10:30:00Z",
  "entrypoint": "skills/weather/SKILL.md",
  "linkedAgents": ["ops-agent"],
  "validationError": null
}
```

详情在此基础上增加 `license`、`compatibility`、`metadata`、`allowedTools`、`resources`、`fileCount`、`totalBytes`。`entrypoint/resources` 均相对 `.oryxos/` 或包根；不返回正文。

### AgentSkillAssociationView

```json
{
  "agentName": "ops-agent",
  "skillName": "weather",
  "description": "查询天气并给出出行建议",
  "link": "agents/ops-agent/skills/weather",
  "target": "../../../skills/weather",
  "linkStatus": "valid",
  "skillStatus": "enabled",
  "discoverable": true,
  "error": null
}
```

无效/悬空关联可以出现在 Agent 列表中，但 `discoverable=false`；错误只返回稳定 code 与安全消息。

## 2. 公共 Skill 资源

Base path：`/api/v1/skills`。

### 列表与详情

```http
GET /api/v1/skills
GET /api/v1/skills/{skillName}
```

列表按 Skill 名排序；详情不存在返回 404。公共根下含 `SKILL.md` 或保留 marker 的直接真实目录是受管候选；坏包以 `invalid` 单项返回，不阻断集合。

### 导入

```http
POST /api/v1/skills
Content-Type: multipart/form-data

file=<single ZIP>
```

成功返回 `PublicSkillDetailView`，默认 enabled。浏览器使用 `FormData.append("file", file)`，不得手工设置 boundary。

| 条件 | HTTP |
|---|---:|
| 缺 part、空文件、坏 ZIP/metadata/路径/类型 | 400 |
| 同名公共路径存在 | 409 |
| ZIP、解压量、单文件、entries 或解压比超限 | 413 |
| 原子发布或未预期 I/O 失败 | 500（安全通用消息） |

现有 `POST /api/v1/skills/import` GitHub 入口若保留，属于兼容 API，必须最终调用相同 `prepare → validate → publish` 公共导入服务；本 Feature 不扩展其 URL 契约。

### 全局启用/禁用

```http
PUT /api/v1/skills/{skillName}
Content-Type: application/json

{ "enabled": false }
```

disable 创建公共包 marker，保留全部 Agent 链接；enable 先完整复验再删除 marker。操作幂等，成功返回最新详情。不存在 404，invalid enable 400。变更从所有关联 Agent 的下一次顶层请求生效。

### 普通删除

```http
DELETE /api/v1/skills/{skillName}
```

无关联时归档公共包并返回：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "skillName": "weather",
    "forced": false,
    "affectedAgents": [],
    "archived": true
  },
  "timestamp": 1784889000000
}
```

发现关联时不修改文件系统，返回 HTTP 409：

```json
{
  "code": 409,
  "message": "Skill is still associated with Agents",
  "data": {
    "reasonCode": "SKILL_IN_USE",
    "skillName": "weather",
    "linkedAgents": ["ops-agent", "support-agent"]
  },
  "timestamp": 1784889000000
}
```

`linkedAgents` 是锁内全量扫描结果，排序、去重。前端必须用它展示影响范围，不能在收到 409 后自动强删。

### 强制删除

```http
DELETE /api/v1/skills/{skillName}?force=true
```

服务端不能信任上次 409 的列表，必须在图谱写锁下重新扫描并移除全部标准关联，再归档公共包。成功：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "skillName": "weather",
    "forced": true,
    "affectedAgents": ["ops-agent", "support-agent"],
    "archived": true
  },
  "timestamp": 1784889060000
}
```

不存在为 404。同进程补偿无法完整完成或其它 I/O 失败为 500，响应不得暴露路径；客户端可重新查询实际关联/包状态后重试。本期不提供持久化删除 journal 或启动恢复。

## 3. Agent 关联资源

Base path：`/api/v1/agents/{agentName}/skills`。

### 实际关联列表

```http
GET /api/v1/agents/{agentName}/skills
```

返回 `ApiResponse<List<AgentSkillAssociationView>>`，内容仅来自 Agent `skills/` 下实际链接，不读取 `AGENT.md skills:`。Agent 不存在/已归档为 404。前端将此列表与 `GET /api/v1/skills` 合并，即可显示“已关联”和“可关联”。

### 建立关联

```http
PUT /api/v1/agents/{agentName}/skills/{skillName}
```

无需 request body。成功创建精确相对链接并返回 association view；相同标准链接已存在时幂等成功。Agent/Skill 不存在 404，Skill invalid 为 400，link path 被普通文件、真实目录或非标准链接占用为 409。全局 disabled Skill 可以关联，但 `discoverable=false`。

### 解除关联

```http
DELETE /api/v1/agents/{agentName}/skills/{skillName}
```

只删除复验后的标准链接，返回被解除的 association view 或 `{agentName, skillName, removed:true}`。Agent/关联不存在为 404；路径存在但不是标准链接为 409，不得跟随或删除占位内容。

既有逆向接口 `/api/v1/skills/{skillName}/agents/{agentName}` 若为兼容而保留，必须标记 deprecated 并委托上述同一关联服务；不得写 `AGENT.md` 或形成第二套真相。

## 4. Agent 创建语义

Agent 创建 DTO 中的 `skills` 表示“创建完成后要建立的公共关联”。服务先验证所有 Skill，再以一个受控事务创建 Agent 文件与标准链接；任何一步失败回滚整个新 Agent。草稿生成响应和 `AGENT.md` 都不写 `skills:`，也不生成 `example` Skill。

## 5. 前端删除交互（A → B）

1. 用户第一次确认后只调用普通 DELETE。
2. 若 200，刷新列表并结束。
3. 若 `409 + data.reasonCode=SKILL_IN_USE`，弹出强制删除对话框，醒目列出 `linkedAgents`，说明将解除这些 Agent 的链接并归档公共包。
4. 只有用户再次明确确认，才调用 `?force=true`。
5. force 期间禁用按钮；只有 200 才移除行。取消或任何失败均保留页面状态。

## 6. 统一异常映射

| 领域情况 | HTTP |
|---|---:|
| Agent/Skill/标准关联不存在 | 404 |
| 同名公共路径、链接占位、Skill 使用中 | 409 |
| 参数、包、链接或状态校验失败 | 400 |
| 上传/展开资源超限 | 413 |
| 原子操作、同进程补偿或未预期 I/O | 500 |

每个进入 core 管理服务的 mutation 恰写一条 `event=skill.management` 结构化领域事件；Web transport 阶段拒绝的请求不伪造领域事件。OpenAPI 必须描述 multipart、三态、association view、typed 409 和 force delete。
