# Contract: GET /api/v1/health

**Feature**: 052-health-version

本特性对 `/api/v1/health` 做**纯增量**扩展：原字段契约不变，返回体 `data` 内新增两个字段。

## 请求

- 方法 / 路径：`GET /api/v1/health`（不变，不新增端点）
- 鉴权：沿用现状（核心阶段匿名可访问；本特性不收紧也不放宽）

## 响应

- 状态码：`200 OK`（不变）
- Content-Type：`application/json`
- Body：统一 `ApiResponse` 包装，`data` 内扩展版本字段

### 变更前（基线）

```json
{
  "code": 0,
  "message": "ok",
  "data": { "status": "ok" }
}
```

### 变更后

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "status": "ok",
    "version": "0.1.8-RELEASE",
    "build": "a1b2c3d"
  }
}
```

## 契约规则

| 规则 | 说明 | 来源 |
|------|------|------|
| C-1 | `data.status` 字段名、位置、取值语义不变 | FR-005 / US2 |
| C-2 | `data.version` 必返回且非空字符串 | FR-001 / FR-004 |
| C-3 | `data.build` 必返回且非空字符串 | FR-002 / FR-004 |
| C-4 | 同一实例多次请求，`version`/`build` 取值一致 | FR-003 |
| C-5 | 版本/构建不可得时字段取占位值（如 `unknown`/`dev`），不缺字段、不为 null、不报错 | FR-004 |
| C-6 | 路径、HTTP 状态语义、鉴权要求不变；不新增端点 | FR-005 / FR-006 |

## 兼容性断言（供测试与验收引用）

- 只读原字段的调用方（监控/K8s 探针）零改动仍工作：响应必含 `data.status == "ok"`。
- 新增字段对老调用方透明：JSON 多出的 key 不影响其解析。
