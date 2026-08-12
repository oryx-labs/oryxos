# HTTP and Prompt Contract: Agent Skill Bindings

所有 HTTP 响应继续使用现有 `ApiResponse<T>` 信封。路径中的 Agent/Skill 名仅允许
`[A-Za-z0-9_-]+`。

## 1. 已安装 Skill 与外部 catalog

### 已安装实体

`GET /api/v1/skills`

只返回 `.oryxos/skills/<name>/` 中校验成功的本机实体；不混入未安装 catalog 项或归档 Skill。

### 候选 catalog

`GET /api/v1/skills/catalog?q=&visibility=all|public|private`

成功 `200`：

```json
{
  "code": 0,
  "data": [
    {
      "name": "report-format",
      "description": "结构化报告规范",
      "visibility": "PUBLIC",
      "source": "team-catalog",
      "installed": true
    }
  ]
}
```

- OryxOS 只消费 catalog adapter 已过滤的结果，不执行 owner/scope/ACL。
- 公共/私有同名候选必须作为冲突拒绝，不得按 visibility 建第二命名空间。
- 作者模型只能看到 `installed=true` 且本机实体再次校验有效的交集。
- catalog 不可用时返回 `503`；不得把未经 catalog 校验的模型名称降级为可绑定项。
- 本阶段不因 catalog 结果自动联网安装 Skill。

## 2. 查询 Agent 绑定

`GET /api/v1/agents/{agent}/skills`

成功 `200`：

```json
{
  "code": 0,
  "data": {
    "bindings": [
      {
        "name": "report-format",
        "description": "结构化报告规范",
        "skillFile": "/workspace/.oryxos/agents/reporter/skills/report-format/SKILL.md"
      }
    ],
    "issues": []
  }
}
```

`skillFile` 必须是 Agent 本地 lexical absolute 路径，不得替换成公共实体 realpath。Agent 不存在返回
`404`。

## 3. 单项绑定与解绑

### 绑定

`PUT /api/v1/agents/{agent}/skills/{skill}`

- 创建固定相对软连接；同一合法链接重复调用幂等。
- 成功 `200`，返回最新绑定快照。
- Agent/Skill 不存在返回 `404`。
- 名称非法、实体无效、catalog 不可见、槽位被普通文件/错误链接占用返回 `400`。

### 解绑

`DELETE /api/v1/agents/{agent}/skills/{skill}`

- 只删除 Agent 本地受控链接；不存在时幂等。
- 成功 `200`，返回最新绑定快照。
- 不得删除公共实体；槽位不是软连接时返回 `400`，不得代删。

## 4. 原子替换绑定集合

`PUT /api/v1/agents/{agent}/skills`

请求：

```json
{ "skills": ["report-format", "web-research"] }
```

- 后端先验证全部名称、catalog 可见性、安装实体和目标槽位，再计算增删集合。
- 任一校验或 IO 失败，返回 `400`/`503`，迁移前绑定集合保持不变。
- 成功 `200`，返回最新绑定快照。

## 5. 全工作区一致性问题

`GET /api/v1/skills/binding-issues`

成功 `200`，`data` 为稳定排序的 `SkillBindingIssue[]`；同时覆盖活跃 Agent、平铺归档 Agent 和 legacy
frontmatter 残留，排除 `.oryxos/archive/skills/`。空数组表示一致。

```json
{
  "code": 0,
  "data": [
    {
      "agentName": "ops",
      "agentState": "ACTIVE",
      "entryName": "broken",
      "path": "/workspace/.oryxos/agents/ops/skills/broken",
      "type": "DANGLING",
      "message": "绑定目标不存在"
    }
  ]
}
```

## 6. Skill 删除即归档

`DELETE /api/v1/skills/{skill}`

无引用时成功 `200`：

```json
{
  "code": 0,
  "data": {
    "name": "report-format",
    "archivedPath": "archive/skills/report-format-20260727T091500Z",
    "archivedAt": "2026-07-27T09:15:00Z"
  }
}
```

存在活跃或归档引用时返回 `409 Conflict`，安装实体和所有链接保持不变：

```json
{
  "code": 409,
  "message": "Skill 仍被 Agent 引用",
  "data": {
    "name": "report-format",
    "references": [
      {
        "agentName": "ops",
        "state": "ACTIVE",
        "directoryName": "ops",
        "linkPath": "/workspace/.oryxos/agents/ops/skills/report-format"
      },
      {
        "agentName": "reporter",
        "state": "ARCHIVED",
        "directoryName": "reporter-1750000000000",
        "linkPath": "/workspace/.oryxos/archive/reporter-1750000000000/skills/report-format"
      }
    ]
  }
}
```

## 7. Agent 创建与作者生成

### 直接创建

`POST /api/v1/agents`

```json
{
  "name": "reporter",
  "description": "生成日报",
  "skillBindings": ["report-format"]
}
```

创建文件、绑定、注册或调度任一步失败时回滚整个新 Agent，不留下目录或链接。

### 生成草稿

`POST /api/v1/agents/{name}/generate-files`

请求：

```json
{
  "description": "每天生成技术日报",
  "notifyChannel": "team-webhook",
  "requiredSkills": ["report-format"]
}
```

响应 sidecar：

```json
{
  "code": 0,
  "data": {
    "files": {
      "AGENT.md": "---\nname: reporter\n...\n---\n按职责执行"
    },
    "requiredSkills": ["report-format"],
    "suggestedSkills": ["web-research"],
    "bindingSkills": ["report-format", "web-research"]
  }
}
```

- required 必须原样保留。
- suggested 必须来自本次交给作者模型的已安装 catalog 交集；列表外名称拒绝整次生成并返回可读错误。
- `AGENT.md` 不得含 top-level `skills:`，files 不得含 `skills/**`。
- sidecar 不落盘，不是绑定真相源。

### 保存草稿

`POST /api/v1/agents/{name}/files`

```json
{
  "files": { "AGENT.md": "..." },
  "skillBindings": ["report-format", "web-research"]
}
```

后端重新校验文件和全部绑定后一次提交。已有 Agent 未提供 `skillBindings` 时保留当前绑定；显式提供空
数组表示解绑全部。

## 8. Agent 视图

既有 `GET /api/v1/agents` 和 `GET /api/v1/agents/{agent}` 可保留 `skills` 字段兼容 UI，但值必须由
`AgentSkillBindingReader` 实时投影，不能从 Profile、frontmatter 或 Registry 获取。

## 9. Prompt contract

存在有效绑定时，Level 1 system prompt 追加：

```text
你可以按需使用以下 Skill。仅在当前任务需要时，用 read_file 读取给出的 SKILL.md；
其中的相对资源路径以该 SKILL.md 所在目录为基准并转换成绝对路径，不要猜测未读取的内容：
- report-format：结构化报告规范
  SKILL.md：/workspace/.oryxos/agents/reporter/skills/report-format/SKILL.md
```

约束：

- 每次 provider 调用前重新扫描，按名称稳定排序。
- 不出现公共 realpath、Skill 正文、附属资源内容或未绑定 Skill。
- 没有有效绑定时不输出标题。
- 问题项只进入日志/一致性 API，不进入 prompt。
- Level 2 只能通过既有 `read_file`；结果按普通 Tool Result 审计并进入下一轮 history。
- Level 3 继续通过 `read_file`/`shell` 按需获取，不新增 `use_skill`。

## 10. Workspace file contract

- `/api/v1/workspace/tree` 遇软连接返回 `type: "link"` 的叶节点，包含 lexical target 和有效性；不递归
  跟随。
- `/file`、`/download`、`/write` 在 IO 前做真实路径边界校验。
- 通过 `agents/<agent>/skills/**` 的管理台写请求返回 `400`；共享 Skill 只能从顶层 Skill 管理入口
  更新。
- 合法工作区内链接可由模型 `read_file` 读取；越界、dangling 或 link cycle 一律拒绝。

## 11. Startup migration contract

- 每个 Agent 独立迁移；合法旧 `skills:` 转为绑定并从 AGENT.md 移除。
- 任一旧名称或槽位无效时，该 Agent 原始 AGENT.md 字节与迁移前链接集合保持不变，产生
  `STALE_REFERENCE` 报告；其它 Agent 继续迁移和启动。
- 新建/更新 API 若收到 top-level `skills:` 直接返回 `400`；只有启动迁移器接受该旧字段。
