# Contract: Agent Skill REST API

Base path：`/api/v1/agents/{agentName}/skills`。所有端点延续 OryxOS 统一信封；成功 HTTP 200 且 `code=0`。

## 1. 统一信封

成功：

```json
{
  "code": 0,
  "message": "success",
  "data": {},
  "timestamp": 1784716200000
}
```

失败：

```json
{
  "code": 400,
  "message": "SKILL.md is missing",
  "data": null,
  "timestamp": 1784716200000
}
```

失败消息只能包含 Agent/Skill 的规范名称、包内相对路径和稳定校验描述；不得包含工作区绝对路径、堆栈、原始包内容或密钥。

## 2. DTO

### SkillSummaryView

```json
{
  "name": "weather",
  "directoryName": "weather",
  "description": "查询天气并给出出行建议",
  "status": "enabled",
  "configuredEnabled": true,
  "catalogIncluded": true,
  "source": "upload",
  "updatedAt": "2026-07-22T10:30:00Z",
  "validationError": null
}
```

- `name`: metadata name；metadata 无法解析时回退为安全显示的 `directoryName`。
- `directoryName`: 管理 member 的稳定键；合法包必须与 `name` 相同。
- `status`: `enabled | disabled | invalid`。
- `configuredEnabled`: disabled marker 是否缺失；invalid 时也返回，便于先禁用再修复。
- `catalogIncluded`: 当前聚合预算下是否进入下一请求的 L1。
- `source`: `upload | workspace`。
- `validationError`: null 或不含绝对路径的 `{ "code": "...", "message": "..." }`。

### SkillDetailView

在 summary 字段上增加：

```json
{
  "entrypoint": "skills/weather/SKILL.md",
  "license": "Apache-2.0",
  "compatibility": "Requires read_file",
  "metadata": { "author": "example-team" },
  "allowedTools": "read_file shell",
  "resources": ["SKILL.md", "references/api.md", "scripts/fetch.sh"],
  "fileCount": 3,
  "totalBytes": 4812
}
```

`entrypoint` 是 Agent 相对路径（`skills/weather/SKILL.md`）；`resources` 是 Skill 包根相对路径（`SKILL.md`、`references/api.md`）。资源列表按 Unicode code point 字典序，且与统计一起排除 `.oryxos-*` 保留状态文件；API 不返回 `SKILL.md` 正文或保留文件内容。

### SetSkillEnabledRequest

```json
{ "enabled": false }
```

`enabled` 必填且必须是 JSON boolean。

## 3. GET collection

```http
GET /api/v1/agents/{agentName}/skills
```

响应：`ApiResponse<List<SkillSummaryView>>`，按 `directoryName` 升序。只列真实直接子目录中含 `SKILL.md` 或 OryxOS 保留 marker 的受管候选；根 symlink 不跟随、不列出并单独告警，也不列 `skills/*.md`、无入口/marker 的 legacy 目录或归档。

错误：Agent 不存在/已归档为 404。

## 4. POST collection：导入

```http
POST /api/v1/agents/{agentName}/skills
Content-Type: multipart/form-data; boundary=...

file=<single .zip part>
```

- part 名固定为 `file`，必填且非空。
- `originalFilename` 不参与身份计算。
- 成功返回导入后的 `SkillDetailView`；合法包默认 `enabled`，从下一次顶层请求生效。
- 浏览器必须用 `FormData.append("file", file)`，不得手工设置 multipart Content-Type/boundary。

错误：

| 条件 | HTTP / code |
|---|---:|
| Agent 不存在/已归档 | 404 |
| 同名 enabled/disabled/invalid Skill 或已存在的 unmanaged 目标目录 | 409 |
| 缺 part、空文件、非 ZIP、结构/metadata/路径/链接/类型非法 | 400 |
| 压缩大小、解压量、单文件、entry 数或解压比超限 | 413 |
| 不支持原子移动或未预期 I/O | 500（通用消息） |

失败时活动目录、Skill 列表和既有包内容不变；staging 最终被清理。

## 5. GET member

```http
GET /api/v1/agents/{agentName}/skills/{skillName}
```

成功返回 `SkillDetailView`。Agent 或 Skill 不存在为 404。对合法包，`skillName` 等于标准 metadata name；为能清理手工产生的 invalid 候选，GET/PUT/DELETE 也接受其实际 `directoryName`。客户端必须对该单段使用 `encodeURIComponent`。服务端必须验证解码值是 `skills/` 下一个现存直接子目录（拒绝空值、`.`、`..`、斜杠、反斜杠、NUL、链接和 normalize 后换父目录），而不是把任意字符串拼成路径。

## 6. PUT member：启用/禁用

```http
PUT /api/v1/agents/{agentName}/skills/{skillName}
Content-Type: application/json

{ "enabled": true }
```

- disable：创建保留 marker；对已 disabled 项幂等。
- enable：先重新完整校验包和聚合预算，成功后移除 marker；对已 enabled 且仍合法项幂等。
- invalid + `enabled=false`：允许写 marker，仍返回 `status=invalid, configuredEnabled=false`。
- invalid + `enabled=true`：校验失败返回 400，marker 和文件保持不变。
- 变更从下一次顶层请求生效；若已有请求持有读租约，本请求同步等待写租约。

成功返回最新 `SkillDetailView`。Agent/Skill 不存在 404；非法内容或预算不足 400。

## 7. DELETE member

```http
DELETE /api/v1/agents/{agentName}/skills/{skillName}
```

成功把 enabled、disabled 或 invalid 包归档并返回：

```json
{
  "code": 0,
  "message": "success",
  "data": null,
  "timestamp": 1784718000000
}
```

`timestamp` 沿用现有 `ApiResponse`，类型为 Unix epoch milliseconds。删除不存在项为 404，且不创建归档事件。已有请求持有读租约时同步等待；成功后下一请求不再发现。恢复不属于本 API。

## 8. HTTP 异常映射

`GlobalExceptionHandler` 增加：

- `SkillConflictException` → 409；
- `SkillPackageTooLargeException` 和 Spring `MaxUploadSizeExceededException` → 413；
- Skill 领域参数/校验异常 → 400；
- Agent/Skill not found → 404。

Controller 用 `@RequestPart(name="file", required=false)` 后手工处理缺失/空 part，避免框架异常落入 500。所有错误回归必须断言响应中没有 `/Users/`、`/private/`、工作区根或堆栈文本。

## 9. OpenAPI 与管理日志

- springdoc 必须展示 multipart `file`、三态字段和 400/404/409/413 响应。
- 每个实际进入 core service 的 POST/PUT/DELETE mutation 只由 service 记录一条 `skill.management` 事件；multipart 超限、缺 part、坏 JSON/路径等 transport rejection 仅由 Web 错误日志记录，避免 core 未执行却伪造领域事件。GET 不写管理事件。
