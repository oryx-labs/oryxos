# Feature Specification: 管理台概览页动态数据接入

**Feature Branch**: `feat/add-overview-source`

**Created**: 2026-07-30

**Status**: Draft

**Input**: 概览页数据当前为静态硬编码，需接入实时 API 实现动态数据展示。参照 `oryxos-web/src/main/frontend/src/App.vue` 的 TODO：
- Agent 数量 ← GET /api/v1/profiles
- Tool 数量 ← GET /api/v1/tools
- 活跃会话数 ← 会话统计端点
- Provider 数量 ← GET /api/v1/info

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 管理员查看实时概览数据 (Priority: P1)

管理员打开管理台概览页时，看到的是实时反映系统当前状态的数据，而非硬编码的静态占位值。四项核心统计卡（Agent、Tool、活跃会话、Provider）均从实时端点动态获取并展示。

**Why this priority**: 这是概览页存在的核心价值——让管理员一眼了解系统运行规模。静态占位数据无法传递任何真实信息。

**Independent Test**: 启动 OryxOS 并配置若干 Profile，打开管理台概览页，验证四项统计卡数值与 `GET /profiles`、`GET /tools`、`GET /sessions/stats`、`GET /info` 返回数据一致。

**Acceptance Scenarios**:

1. **Given** 系统已配置 3 个 Agent Profile，**When** 管理员打开概览页，**Then** "Agent" 统计卡显示 "3"。
2. **Given** Tool 注册表包含 9 个内置 Tool + 2 个 MCP Tool，**When** 管理员打开概览页，**Then** "内置 Tool" 统计卡显示 "11"。
3. **Given** 系统有 5 个 active 状态的会话，**When** 管理员打开概览页，**Then** "活跃会话" 统计卡显示 "5"。
4. **Given** 系统配置了 2 个 Provider，**When** 管理员打开概览页，**Then** "Provider" 统计卡显示 "2"。

---

### User Story 2 - 概览数据异常时的兜底展示 (Priority: P2)

当某个后端端点不可用或返回错误时，概览页对应统计卡展示明确的加载中/错误状态，不影响其余正常的统计卡继续展示。

**Why this priority**: 部分端点故障不应导致整个概览页不可用，且管理员需要知道哪个数据源出了问题。

**Independent Test**: 在不启动 Provider 后端的情况下，概览页仍能展示 Agent 数量（来自正常的 `/profiles` 端点），而 Provider 统计卡显示 "—" 或错误提示。

**Acceptance Scenarios**:

1. **Given** `/api/v1/tools` 返回 500，**When** 管理员打开概览页，**Then** "内置 Tool" 统计卡显示 "—" 或错误标记，其余统计卡正常展示。
2. **Given** 所有端点正常，**When** 概览页加载完成，**Then** 不再显示 "当前为静态预览数据" 提示。

---

### Edge Cases

- 某端点返回空数组（如零个 Profile）时，统计卡显示 "0" 而非 "—"，与"未加载"状态区分。
- 网络极慢场景下，统计卡先展示加载态（如 "..."），有值后替换。
- `GET /api/v1/info` 已被 `loadRuntimeInfo()` 单独获取，需避免重复请求。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 概览页 MUST 从 `GET /api/v1/profiles` 获取 Agent 数量（`data.length`）并展示在 "Agent" 统计卡。
- **FR-002**: 概览页 MUST 从 `GET /api/v1/tools` 获取 Tool 数量（`data.length`）并展示在 "内置 Tool" 统计卡。
- **FR-003**: 概览页 MUST 从 `GET /api/v1/info` 获取 Provider 数量（`data.providers.length`）并展示在 "Provider" 统计卡。可复用已有 `runtimeInfo` 数据，避免重复请求。
- **FR-004**: 系统 MUST 提供会话统计端点（`GET /api/v1/sessions/stats`），返回 `{ active: N, archived: M, total: T }`，概览页从中取 `active` 展示。
- **FR-005**: `SessionManager` 接口 MUST 新增 `stats()` 方法，返回活跃/归档/总会话计数。
- **FR-006**: 概览页 `overview` 对象 MUST 改为响应式数据（`reactive`/`ref`），四个统计卡的 value MUST 从上述端点动态获取。
- **FR-007**: 概览页 MUST 为每个统计卡独立处理加载中/错误/正常三种状态，单一端点失败不阻断其余。
- **FR-008**: 统计卡 hint 文本 MUST 随动态数据更新：Tool hint 从 "14" 改为实际数量描述；活跃会话 hint 从 "待接入实时统计" 改为 "当前活跃"。
- **FR-009**: 概览页 MUST 在全部动态数据加载完成后移除 "当前为静态预览数据" 提示。

### Key Entities

- **会话统计 (SessionStats)**: `active`（活跃会话数）、`archived`（归档会话数）、`total`（总会话数）。由新增的 `GET /api/v1/sessions/stats` 端点返回。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 概览页四项统计卡 100% 展示实时动态数据，无硬编码值。
- **SC-002**: 单个端点故障时，受影响统计卡显示兜底状态，其余正常展示。
- **SC-003**: 概览页打开后 3 秒内完成所有数据加载（正常网络条件）。
- **SC-004**: 不再显示 "当前为静态预览数据，将逐步接入实时端点" 这行提示。

## Assumptions

- 现有 `GET /api/v1/profiles`、`GET /api/v1/tools`、`GET /api/v1/info` 返回格式稳定，直接复用。
- 新增的 `GET /api/v1/sessions/stats` 端点遵循现有 `ApiResponse<T>` 统一响应体格式。
- 前端通过并行 `fetch` 调用获取四项数据，不做服务端聚合。
- `runtimeInfo` 的 `GET /api/v1/info` 调用保留在 `loadRuntimeInfo()` 中，概览统计卡直接读取 `runtimeInfo.data.providers.length`。
