# Tasks: 新建 Agent 时的已安装 Skill 查询筛选

**Input**: Design documents from `/specs/028-agent-skill-filter/`（[spec.md](spec.md) · [plan.md](plan.md) · [research.md](research.md) · [data-model.md](data-model.md) · [contracts/skill-picker.md](contracts/skill-picker.md) · [quickstart.md](quickstart.md)）

**Prerequisites**: plan.md、spec.md、research.md、data-model.md、contracts/skill-picker.md 均已就绪。

**Tests**: 本特性在 plan.md R4 中已承诺为纯函数补 `node --test` 单测（沿用既有 `chat-scroll.test.js` 同模式），故纳入对应任务；UI 交互走 quickstart 手测，不引 @vue/test-utils。

**Organization**: 按用户故事分组——纯函数与共享态（多故事共用）放 Foundational；US1/US2（P1）先做新建页；US3（P2）加批量；US4（P2）把全套行为移植到详情编辑页。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成任务依赖）
- **[Story]**: 归属用户故事（US1~US4）；Setup/Foundational/Polish 不标
- 文件路径用仓库相对路径

## Path Conventions

- 前端资源在 `oryxos-web/src/main/frontend/`（Vue 3 单文件 `src/App.vue` + 旁挂纯函数模块 `src/*.js` + `src/*.test.js`）。
- 无 Java/后端改动、无新 REST 端点、无新表/列。

---

## Phase 1: Setup

**Purpose**: 接入测试运行入口（既有 `node --test` 现状只跑 `chat-scroll.test.js`）。

- [X] T001 在 `oryxos-web/src/main/frontend/package.json` 的 `scripts.test` 中追加 `src/skill-filter.test.js`，使其与既有 `chat-scroll.test.js` 一并被 `npm run test` 跑到（保持 `node --test` 既有模式，不引新依赖）

**Checkpoint**: `npm run test` 仍全绿（新测试文件此时不存在会报错属预期——T002 建好即过）。

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 多故事共用的纯函数与共享筛选态——所有用户故事都依赖它们，**必须先完成**。

**⚠️ CRITICAL**: 未完成本 phase 前 US1~US4 不得开工。

- [X] T002 [P] 新建 `oryxos-web/src/main/frontend/src/skill-filter.js`，导出纯函数 `filterSkills(list, query)`：去首尾空格、空串原样返回、按 `name` OR `description` 不区分大小写包含匹配、`description` 缺失仅按 `name` 匹配不报错（FR-002/FR-005，data-model.md §filterSkills）
- [X] T003 [P] 在 `oryxos-web/src/main/frontend/src/skill-filter.js` 追加 `hiddenSelectedCount(visible, selected)`：返回 `selected` 中不在 `visible.name` 集合内的数量（FR-004a 提示计数，data-model.md §hiddenSelectedCount）
- [X] T004 [P] 在 `oryxos-web/src/main/frontend/src/skill-filter.js` 追加 `selectAllVisible(visible, selected)` 与 `clearVisible(visible, selected)`：前者并集去重、后者差集；作用域=当前视野，视野外已选项不变（US3，data-model.md §批量动作）
- [X] T005 [P] 新建 `oryxos-web/src/main/frontend/src/skill-filter.test.js`，沿用既有 `chat-scroll.test.js` 的 `node --test` 风格，覆盖：空串全返 / 去首尾空格 / 大小写 / name+description 命中 / 空描述仅按 name / `hiddenSelectedCount` 计数 / `selectAllVisible`·`clearVisible` 作用域与去重（quickstart V5）
- [X] T006 在 `oryxos-web/src/main/frontend/src/App.vue` 新增共享 reactive `skillFilter = reactive({ query: '', showHidden: false })`，与既有 `skills`/`agentCreate`/`agentBinding` 同级（research.md R2）；从 `skill-filter.js` import 上述纯函数

**Checkpoint**: `npm run test` 全绿（V5）；纯函数与共享态就位，用户故事可开工。

---

## Phase 3: User Story 1 - 按关键词筛出要绑定的 Skill (Priority: P1) 🎯 MVP

**Goal**: 新建 Agent 的 Skill 绑定区出现搜索框，输入实时收窄已安装 Skill 列表，空结果显示明确提示。

**Independent Test**: 安装 30 个 Skill（5 个含 "pr"），打开新建 Agent 页输入 "pr" → 列表只剩这 5 个；输入不存在词显示「无匹配 Skill」；清空恢复全部。（quickstart V1）

### Implementation for User Story 1

- [X] T007 [US1] 在 `oryxos-web/src/main/frontend/src/App.vue` 新建 Agent 视图的「Skill 绑定」`.skill-picker` 上方加一个文本搜索框，`v-model` 绑 `skillFilter.query`（FR-001）
- [X] T008 [US1] 将该处 `.skill-picker` 内 `v-for="s in skills.data"` 改为基于 `filterSkills(skills.data, skillFilter.query)` 的计算结果渲染（FR-002）；`v-model` 仍绑 `agentCreate.skills`（为 US2 的「不丢已选」留好解耦基础）
- [X] T009 [US1] 在筛选结果为空时渲染明确的「无匹配 Skill」提示替代空白（FR-003）；空 `query` 时行为与未加搜索前一致（FR-005）

**Checkpoint**: 新建页搜索收窄 + 空结果提示可独立验收（V1）。

---

## Phase 4: User Story 2 - 筛选不丢已勾选状态 + 隐藏提示 (Priority: P1)

**Goal**: 被筛选隐藏的已选项保持选中（FR-004），并显示「N 项已选被当前筛选隐藏」提示、可点击临时纳入视野（FR-004a）。

**Independent Test**: 勾 A、B，输入只命中 C 的词 → 列表只剩 C 但 A、B 仍选中、出现「隐藏 2 项已选」提示；点提示 A、B 纳入视野且仍勾选；清空后 A、B 仍勾选。（quickstart V2）

**Depends on**: US1（搜索框与 filterSkills 渲染须先就位）。

### Implementation for User Story 2

- [X] T010 [US2] 核验/保证 `v-model` 绑定不变（仍 `agentCreate.skills`），仅渲染集换成过滤子集——验证被隐藏的已选项勾选态被保留（FR-004，research.md R3「选择集与显示集解耦」）
- [X] T011 [US2] 在新建页 `.skill-picker` 区，当 `hiddenSelectedCount(visible, agentCreate.skills) > 0` 时渲染「当前筛选隐藏了 N 项已选」提示（FR-004a）；计数随 `query`/勾选实时刷新（Edge「计数实时更新」）
- [X] T012 [US2] 点击提示置 `skillFilter.showHidden = true`，渲染集改为「`visible` ∪ `selected` 中被隐藏者（按 name 去重）」，选中态不变、不清空 `query`（FR-004a，data-model.md §renderSet）

**Checkpoint**: 新建页「边选边筛不丢 + 隐藏提示 + 纳入视野」可独立验收（V2）。

---

## Phase 5: User Story 3 - 批量勾选当前筛选结果 (Priority: P2)

**Goal**: 对当前筛选视野内项一键全选 / 一键清空，视野外已选项不受影响。

**Independent Test**: 搜索 "git" 得 4 项 → 「全选当前」4 项全勾、视野外已选项不变；「清空当前」仅取消这 4 项；空结果时两动作禁用。（quickstart V3）

**Depends on**: US1（filterSkills 视野须先就位）；纯函数 `selectAllVisible`/`clearVisible` 已在 Foundational。

### Implementation for User Story 3

- [X] T013 [US3] 在新建页 `.skill-picker` 区加「全选当前 / 清空当前」两个按钮，分别调用 `selectAllVisible(visible, agentCreate.skills)` 与 `clearVisible(visible, agentCreate.skills)`（FR-006，data-model.md §批量动作）
- [X] T014 [US3] 当前筛选视野为空时两按钮 `disabled`（US3 场景 3）；点击后 `agentBinding.saved=false` 同型——此处新建页无 saved 态，仅刷新勾选即可

**Checkpoint**: 批量全选/清空当前视野可独立验收（V3）。

---

## Phase 6: User Story 4 - Agent 详情页编辑绑定时同样可筛选 (Priority: P2)

**Goal**: 详情页「基本信息 > skills」编辑绑定区复用同一选择器、同一筛选行为（含隐藏提示与批量）。

**Independent Test**: 打开任一已存在 Agent 详情 → 基本信息 skills 区出现与新建页一致的搜索框；输入关键词列表收窄、已绑定项 X 被隐藏但保留勾选、清空后 X 仍勾选；保存绑定 `PUT /agents/{name}/skills` body 含全部 `selected`（含曾隐藏的 X）。（quickstart V4）

**Depends on**: US1~US3（行为模板与纯函数已定型）。

### Implementation for User Story 4

- [X] T015 [US4] 在 `oryxos-web/src/main/frontend/src/App.vue` 详情页 `agentBinding` 的 `.skill-picker` 区，复制新建页的搜索框 + `filterSkills` 渲染 + 「无匹配 Skill」提示；`v-model` 仍绑 `agentBinding.selected`，`@change="agentBinding.saved = false"` 保留（FR-007）
- [X] T016 [US4] 在该编辑区加「N 项已选被当前筛选隐藏」提示与 `skillFilter.showHidden` 纳入视野行为（与 US2 同型，FR-004a）；已绑定项被隐藏时保留勾选
- [X] T017 [US4] 在该编辑区加「全选当前 / 清空当前」按钮，作用 `agentBinding.selected`，并 `@change`/点击后置 `agentBinding.saved = false`（与 US3 同型，FR-006）

**Checkpoint**: 编辑页与新建页行为一致可独立验收（V4）；保存绑定仍走既有端点、body 含全部已选（含隐藏项）。

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: 量级、构建门禁、文档收尾。

- [ ] T018 [P] 在 ≥200 个已安装 Skill 下连打 `a`→`ab`→`abc` 手测：每次键入列表即时收窄、键入不卡顿不丢字符（SC-003，quickstart V6）
- [X] T019 跑 `mvn -q -DskipTests package` 验证 frontend-maven-plugin 的 `npm run build`（含 `npm run test`）在构建期全绿（quickstart V5，回归零破坏）
- [ ] T020 跑 quickstart V1~V4 端到端手测，确认 SC-001~SC-004 达成；按需在 `specs/028-agent-skill-filter/` 下补 `acceptance-report.md`（参照 021 同型）
- [X] T021 [P] 在 `CLAUDE.md` 的「核心能力与验收 Demo」或对应节，若本特性构成管理台新行为则补一行说明；无则跳过（避免无谓改动）

**Checkpoint**: 全量构建 + quickstart 全绿；特性交付完成。

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，立即开始。
- **Foundational (Phase 2)**: 依赖 Setup（T001 接好测试入口）；**阻断**所有用户故事。
- **US1 (Phase 3, P1 MVP)**: 依赖 Foundational。
- **US2 (Phase 4, P1)**: 依赖 US1（复用其搜索框与 filterSkills 渲染）。
- **US3 (Phase 5, P2)**: 依赖 US1（filterSkills 视野）；纯函数在 Foundational 已就位。
- **US4 (Phase 6, P2)**: 依赖 US1~US3（行为模板已定型，移植到编辑页）。
- **Polish (Phase 7)**: 依赖全部用户故事完成。

### User Story Dependencies

- **US1 (P1)**: Foundational 完即可开工，无故事间依赖——MVP 切片。
- **US2 (P1)**: 与 US1 同级关键，但技术上建立在 US1 的搜索框/渲染之上（标记 integrates with US1）。
- **US3 (P2)**: 独立于 US2/US4；只依赖 US1 的视野与 Foundational 的批量纯函数。
- **US4 (P2)**: 独立于 US1~US3 的「业务正确性」，但复用其行为模板——建议排在 US3 之后以免重复改编辑页。

### Within Each User Story

- 纯函数（Foundational）先行且单测全绿，再动 `App.vue` 模板。
- 模板改动：搜索框 → 过滤渲染 → 空结果/隐藏提示 → 批量，逐条验收。
- 同一文件 `App.vue` 被多故事触碰——**不要跨故事并行改同一文件**；按 P1→P2 顺序串行。

### Parallel Opportunities

- Foundational 的 T002/T003/T004/T005/T006 互不依赖（T006 import T002~T004 的产物，但可先写好签名后并行填实现）——同文件 `skill-filter.js` 内多函数可并行起草，合并时注意无冲突。
- Polish 的 T018（手测）与 T021（文档）可与 T019/T020 串行收尾时并行。
- 不同用户故事**不建议并行**——均改 `App.vue` 同一文件，并行会产生合并冲突。

---

## Parallel Example: Foundational 纯函数

```bash
# 四个纯函数 + 一个单测可并行起草（不同关注点）：
Task: "filterSkills in src/skill-filter.js"
Task: "hiddenSelectedCount in src/skill-filter.js"
Task: "selectAllVisible/clearVisible in src/skill-filter.js"
Task: "skill-filter.test.js covering all of the above"
```

---

## Implementation Strategy

### MVP First (US1 Only)

1. Phase 1 Setup（T001 接测试入口）。
2. Phase 2 Foundational（T002~T006 纯函数 + 共享态 + 单测）。
3. Phase 3 US1（T007~T009 新建页搜索 + 收窄 + 空结果）。
4. **STOP & VALIDATE**：跑 quickstart V1 + V5，US1 独立可用即 MVP 可交付/演示。

### Incremental Delivery

1. Setup + Foundational → 纯函数单测全绿。
2. + US1 → 新建页可筛选（MVP，V1）。
3. + US2 → 边选边筛不丢 + 隐藏提示（V2）。
4. + US3 → 批量全选/清空当前（V3）。
5. + US4 → 编辑页同型（V4）。
6. Polish → 量级 + 构建 + 文档（V5/V6）。

每一步在前一步不破坏的前提下叠加价值；纯函数单测保证逻辑层回归。

---

## Notes

- [P] = 不同文件、无未完成任务依赖。Foundational 内同文件多函数可并行起草但须合并无冲突。
- 同一 `App.vue` 被多故事触碰 → 故事间串行、勿并行。
- 提交粒度：每个任务或逻辑组一次 commit；每个 Checkpoint 处跑对应 quickstart 段验收。
- 宪法 IV 一致性：本特性不改绑定真相源（相对软连接）、不预载 SKILL.md 正文——实现时若发现需要读 `body` 才能筛，立即停下复核 spec/plan。
