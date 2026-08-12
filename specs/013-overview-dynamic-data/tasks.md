# Tasks: 管理台概览页动态数据接入

**输入**: `specs/013-overview-dynamic-data/` 下的设计文档

**前置条件**: plan.md, spec.md, research.md, data-model.md, contracts/

## 格式: `- [ ] [ID] [P?] [Story?] 任务描述`

- **[P]**: 可并行执行（不同文件，无依赖关系）
- **[Story]**: 所属用户故事（US1、US2）
- 任务描述中必须包含精确的文件路径

---

## Phase 1: 项目初始化（共享基础设施）

**目的**: 无需新建项目结构——仅修改现有模块。

*本阶段无任务。*

---

## Phase 2: 基础设施（后端——会话统计）

**目的**: `SessionStats` 值对象 + `SessionManager.stats()` 接口 + JPA 查询 + 控制器端点。必须在用户故事开始前完成（`/api/v1/sessions/stats` 端点需可供 US1 使用）。

**⚠️ 关键**: 本阶段完成前，任何用户故事均不能开始。

- [x] T001 [P] 在 `oryxos-core/src/main/java/io/oryxos/core/session/SessionStats.java` 中创建 `SessionStats` record，包含 `active`、`archived` 字段和 `total()` 方法
- [x] T002 [P] 在 `oryxos-core/src/main/java/io/oryxos/core/session/SessionManager.java` 的 `SessionManager` 接口中新增 `SessionStats stats()` 方法
- [x] T003 [P] 在 `oryxos-storage/src/main/java/io/oryxos/storage/SessionRepository.java` 的 `SessionRepository` 中新增 `long countByStatus(String status)` 方法
- [x] T004 [P] 在 `oryxos-web/src/main/java/io/oryxos/web/controller/dto/SessionStatsView.java` 中创建 `SessionStatsView` DTO record（`active`、`archived`、`total`）
- [x] T005 在 `oryxos-storage/src/main/java/io/oryxos/storage/JpaSessionManager.java` 的 `JpaSessionManager` 中实现 `stats()` ——委托给 `repository.countByStatus()`
- [x] T006 在 `oryxos-web/src/main/java/io/oryxos/web/controller/SessionApiController.java` 的 `SessionApiController` 中新增 `GET /api/v1/sessions/stats` 端点——调用 `sessionManager.stats()`，返回包装在 `ApiResponse` 中的 `SessionStatsView`

**检查点**: `curl http://localhost:8080/api/v1/sessions/stats` 返回 `{ active, archived, total }`。

---

## Phase 3: User Story 1 — 管理员查看实时概览数据 (Priority: P1) 🎯 MVP

**目标**: 概览页四项统计卡从静态硬编码改为从实时 API 动态获取。

**独立测试**: 启动 OryxOS 后打开管理台概览页，验证 Agent/Tool/活跃会话/Provider 四项数值分别等于 `GET /profiles`、`GET /tools`、`GET /sessions/stats`、`GET /info` 返回的实际值。

### User Story 1 实现

- [x] T007 [US1] 在 `oryxos-web/src/main/frontend/src/App.vue` 中将 `overview` 对象改为 `reactive()` ——stats 变为独立的响应式条目，每个条目包含 `{ value, loading, error }`
- [x] T008 [US1] 在 `oryxos-web/src/main/frontend/src/App.vue` 中新增 `loadOverviewStats()` 函数——并行请求 `GET /api/v1/profiles`（设置 agents 计数）、`GET /api/v1/tools`（设置 tools 计数并提取提示名称）、`GET /api/v1/sessions/stats`（设置 sessions active 计数）；将 `runtimeInfo.data.providers.length` 接入 providers 统计
- [x] T009 [US1] 在 `oryxos-web/src/main/frontend/src/App.vue` 中，当概览页激活（`active === 'overview'`）及手动刷新时调用 `loadOverviewStats()`
- [x] T010 [US1] 动态更新统计卡提示文本——Tool 提示显示响应中的前 3 个工具名称（如 "read_file / shell / http_get …"）；Session 提示显示 "当前活跃"；修改 `oryxos-web/src/main/frontend/src/App.vue`
- [x] T011 [US1] 在 `oryxos-web/src/main/frontend/src/App.vue` 中，当全部四项统计加载完成（loaded===true）后，从概览模板中移除静态预览提示（"当前为静态预览数据，将逐步接入实时端点"）

**检查点**: 此时概览页展示来自四个端点的实时数据，静态预览提示已移除。

---

## Phase 4: User Story 2 — 概览数据异常时的兜底展示 (Priority: P2)

**目标**: 单个端点故障时对应统计卡显示兜底状态（"—"或加载中），不影响其余卡片正常展示。

**独立测试**: 在浏览器 DevTools 中手动阻止某个端点请求，刷新概览页，对应卡片显示"—"，其余三项正常展示。

### User Story 2 实现

- [x] T012 [US2] 在 `oryxos-web/src/main/frontend/src/App.vue` 的统计卡数值插槽中新增每个统计卡的加载状态渲染——`loading` 为 true 时显示 "..."
- [x] T013 [US2] 在 `oryxos-web/src/main/frontend/src/App.vue` 的统计卡数值插槽中新增每个统计卡的错误状态渲染——`error` 被设置时显示 "—"；注意区分三种情况：fetch 成功且数据为空数组时 `value` 设为 `0`（正常展示 "0"），fetch 成功有数据时 `value` 设为 `data.length`（正常展示数字），fetch 失败时设 `error`（展示 "—"）
- [x] T014 [US2] 确保 `oryxos-web/src/main/frontend/src/App.vue` 中 `loadOverviewStats()` 的 catch 块设置每个统计卡的 `error`，且不影响其余并行 Promise

**检查点**: 此时阻止任意单个端点，仅对应卡片显示"—"，其余卡片正常。值为 "0"（空响应）时显示 "0"，与错误态的 "—" 明确区分。

---

## Phase 5: 收尾与跨切面关注点

**目的**: 验证与质量门禁。

- [x] T015 运行 `mvn verify` 确认所有代码质量门禁通过（Spotless + P3C + Checkstyle + SpotBugs）
- [x] T016 对照 `quickstart.md` 验证——执行全部四个验证场景并确认预期结果，同时确认概览页 4 个并行 fetch 均在 3 秒内完成加载（localhost 环境）

---

## 依赖关系与执行顺序

### 阶段依赖

- **Phase 2（基础设施）**: 无依赖——可立即开始。T001-T004 均为 [P]（可并行）。T005 依赖 T003。T006 依赖 T004 + T005。
- **Phase 3（US1）**: 依赖 Phase 2 完成（需 `/api/v1/sessions/stats` 可用）。
- **Phase 4（US2）**: 依赖 Phase 3 完成（错误处理包裹 US1 中的请求逻辑）。
- **Phase 5（收尾）**: 依赖所有前置阶段。

### 用户故事依赖

- **US1（P1）**: 基础设施完成后即可开始，不依赖 US2。
- **US2（P2）**: 依赖 US1——在 US1 构建的请求逻辑之上增加错误/加载状态渲染。

### 基础设施阶段内部

```
T001 [P] ──┐
T002 [P] ──┤ （全部并行）
T003 [P] ──┤
T004 [P] ──┘
            │
            ├── T005 （依赖 T003）
            └── T006 （依赖 T004 + T005）
```

### US1 阶段内部

```
T007 → T008 → T009 （顺序执行——同一文件）
            T010 [P] （模板不同区域）
            T011 [P] （模板不同区域）
```

### 并行机会

- **Phase 2**: T001、T002、T003、T004 可全部并行（4 个不同文件，跨 3 个模块）
- **Phase 3**: T010 和 T011 可在 T009 后并行（模板不同区域）
- **Phase 3 + 4 合并视角**: T009 之后，T010-T011 [US1] 与 T012-T014 [US2] 可部分重叠（若由不同开发者实施并协调 App.vue 的修改）

---

## 实施策略

### MVP 优先（基础设施 + US1）

1. 完成 Phase 2: 基础设施 → `/api/v1/sessions/stats` 端点可工作
2. 完成 Phase 3: US1 → 概览页展示实时数据
3. **停止并验证**: 确认四项统计卡反映系统实际状态
4. 可部署——动态数据概览是核心交付物

### 增量交付

1. 基础设施 → 后端统计端点可用
2. +US1 → 概览动态化（MVP！）
3. +US2 → 故障时优雅降级
4. +收尾 → 质量门禁通过

### 注意事项

- 所有前端任务均修改同一文件（`App.vue`）——编辑时请协调，避免冲突
- US2 本质上是 US1 请求逻辑的错误处理细化；两者共享同一套响应式 stat 对象
- T007-T014 可视为一次原子化的前端修改会话（因全在同一文件），但按可追溯性拆分为独立任务
