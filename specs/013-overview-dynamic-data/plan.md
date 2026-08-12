# Implementation Plan: 管理台概览页动态数据接入

**Branch**: `feat/add-overview-source` | **Date**: 2026-07-30 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/013-overview-dynamic-data/spec.md`

## Summary

管理台概览页当前四项核心统计卡（Agent、Tool、活跃会话、Provider）均为硬编码静态值。本特性将 `overview.stats` 从静态 JavaScript 字面量改为响应式数据，分别接入已有的 `GET /api/v1/profiles`、`GET /api/v1/tools`、`GET /api/v1/info` 三个端点，并新增 `GET /api/v1/sessions/stats` 会话统计端点（返回 `{ active, archived, total }`）。概览页加载时并行请求，每个统计卡独立处理 loading/error/normal 状态。

## Technical Context

**Language/Version**: Java 21（后端）、Vue 3（前端，单文件组件）

**Primary Dependencies**: Spring Boot 3.x、Spring Data JPA、SQLite（持久化）

**Storage**: SQLite — `sessions` 表含 `status` 列（`active`/`archived`），新增 `countByStatus()` JPA 查询

**Testing**: Maven 多模块、`mvn verify`（集成 Spotless + P3C + Checkstyle + SpotBugs）

**Target Platform**: Linux/macOS 服务器，管理台为浏览器 SPA

**Project Type**: Web 前后端（Vue 3 前端 + Spring Boot 后端），管理台前端落在 `oryxos-web/src/main/frontend/`

**Performance Goals**: 概览页加载 4 个并行 fetch 完成 <3 秒（无性能瓶颈，全为本地 SQLite 查询）

**Constraints**: 
- 不引入新的 Maven 依赖或模块
- `overview` 统计卡需独立 loading/error 状态，单端点故障不阻断其余
- 会话统计端点遵循现有 `ApiResponse<T>` 统一响应体
- `runtimeInfo` 的 `/info` 调用可复用，不重复请求

**Scale/Scope**: 单项改动 — `oryxos-core`（接口） + `oryxos-storage`（实现） + `oryxos-web`（端点 + 前端），共约 80 行新增/修改

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. 自实现 ReAct 循环 | ✅ N/A | 本特性不涉及 ReAct 循环 |
| II. Spring AI 仅协议转换与 Schema | ✅ N/A | 不涉及 LLM 调用 |
| III. Provider 显式映射 | ✅ N/A | 不涉及 Provider 路由 |
| IV. 一个目录=一个 Agent，Skill 软连接 | ✅ N/A | 不涉及 Agent 或 Skill |
| V. 审计 Day One 落库 | ✅ N/A | 不涉及 tool/llm 调用 |
| VI. 安全沙箱与真实路径校验 | ✅ N/A | 不涉及文件/Shell/HTTP 工具 |
| VII. 同步执行 + 虚拟线程 | ✅ 遵循 | 会话统计查询为同步 Spring MVC 端点，受虚拟线程支撑 |
| VIII. 目录配置即 Agent，状态外置 | ✅ 遵循 | 会话统计查询 SQLite `sessions` 表，不引入新状态存储 |

**Gate Result (Pre-design)**: ✅ ALL PASS — 无违规，无需复杂性论证。

**Post-Design Re-evaluation**: 设计未引入任何违反原则的元素 — 无新模块、无新存储表、无异步模型、无安全边界变化、无 Agent/Skill 模型变更。全部原则仍维持 PASS。

## Project Structure

### Documentation (this feature)

```text
specs/013-overview-dynamic-data/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   └── sessions-stats-api.md
└── tasks.md             # Phase 2 output (NOT created by plan)
```

### Source Code (repository root)

```text
oryxos-core/src/main/java/io/oryxos/core/session/
└── SessionManager.java          # + SessionStats record, + stats() method

oryxos-storage/src/main/java/io/oryxos/storage/
├── Session.java                 # (existing entity, no changes)
├── SessionRepository.java       # + countByStatus(String status) query
└── JpaSessionManager.java       # + stats() implementation

oryxos-web/src/main/java/io/oryxos/web/controller/
├── SessionApiController.java    # + GET /api/v1/sessions/stats
└── dto/
    └── SessionStatsView.java    # NEW DTO record

oryxos-web/src/main/frontend/src/
└── App.vue                      # overview 对象 → 响应式，+ loadOverviewStats()
```

**Structure Decision**: 现有模块结构已满足需要。`oryxos-core` 定义 `SessionStats` 值对象与 `SessionManager.stats()` 接口；`oryxos-storage` 实现 JPA 计数 + 接口实现；`oryxos-web` 暴露 REST 端点 + 前端修改。不新建模块。

## Complexity Tracking

> 无违规，本表为空。
