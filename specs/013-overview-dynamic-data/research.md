# Research: 管理台概览页动态数据接入

## Decision 1: 会话统计端点设计

**Decision**: 新增 `GET /api/v1/sessions/stats`，返回 `SessionStatsView { active, archived, total }`。

**Rationale**: 
- TODO 明确标注 "sessions←会话统计端点"，要求专用统计端点而非客户端从列表推算。
- 客户端从 `?status=active` 列表推算有上限问题（列表仅取 100 条），会话超过 100 时不准。
- SQLite 的 `SELECT COUNT(*) ... GROUP BY status` 性能极好，一次性返回全部三态计数。
- 专用端点语义清晰，未来可扩展（如按 channel、按 profile 统计）而不用改客户端。

**Alternatives considered**:
- **A) 复用 `GET /api/v1/sessions?status=active` 客户端计数**: 拒绝，原因见上（100 条上限 + 语义不明确）。
- **B) 在 `GET /api/v1/info` 中追加 `sessionCount` 字段**: 拒绝，`/info` 是关于系统本身的元信息，混入业务统计打破单一职责。

## Decision 2: JPA 计数实现方式

**Decision**: 在 `SessionRepository` 中新增 `@Query("SELECT COUNT(s) FROM Session s WHERE s.status = :status")`，`JpaSessionManager.stats()` 调用三次（active/archived/total）。

**Rationale**:
- Spring Data JPA 的 `countByStatus(String status)` 派生查询无需手写 JPQL，简洁可靠。
- 三次 COUNT 查询在 SQLite 上均为顺序扫描，性能良好（会话表通常 <10000 行）。
- 未来如需优化可合并为单次 `GROUP BY` 查询，当前阶段不必要。

**Alternatives considered**:
- **单次 GROUP BY 查询**: SQLite 支持但 Spring Data JPA 派生查询不直接支持 `GROUP BY`，需手写 `@Query`。当前规模不需要优化，且三次独立 COUNT 更清晰。

## Decision 3: 前端响应式数据架构

**Decision**: 将 `overview` 从静态对象改为 `reactive()` 对象，stats 每个条目有独立 `loading/error/value`。概览页进入时并行 3 个 fetch（profiles、tools、sessions/stats），Provider 直接读已有 `runtimeInfo`。

**Rationale**:
- `runtimeInfo`（来自 `loadRuntimeInfo()`）在应用启动时就已获取 `GET /api/v1/info`，其中 `providers` 列表可直接复用而不重复请求。
- 其余三个端点（profiles、tools、sessions/stats）概览页专属，进入时并行加载。
- 每个 stat 独立状态确保单个端点失败不影响其他卡片展示。
- `reactive()` 比 `ref()` 更适合已有 `overview` 嵌套对象的结构。

**Alternatives considered**:
- **单个聚合端点批量返回**: 拒绝，增加后端耦合，且 Spring MVC 不天然支持 GraphQL 式聚合；现有端点已独立可用，前端 `Promise.all` 即可。

## Decision 4: 静态提示移除时机

**Decision**: 概览页最初渲染时显示 "加载中..."，四项统计卡全部完成（成功或失败）后，移除底部 "当前为静态预览数据" 提示。

**Rationale**: 只要数据尝试加载过了（无论成功还是兜底），就不再是"静态预览"。即使某个端点失败，管理员仍能看到能正常加载的统计卡数据，已构成"动态"展示。

## Decision 5: Tool hint 文本更新

**Decision**: Tool 统计卡 hint 文本从 "文件 / Shell / HTTP / 记忆 …" 改为动态形式——当所有请求完成后，hint 显示实际 Tool 列表的前几个名称（如 "read_file / shell / http_get …"），而非硬编码类别。

**Rationale**: 内置 Tool + MCP Tool 总额随 MCP server 配置变化，静态 "14" 换数后发现与动态数字不协调。Tool 名列表（由 `GET /api/v1/tools` 直接返回）更能让管理员了解现有能力。
