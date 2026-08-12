# Tasks: Agent Skill 软连接绑定与三级渐进加载

**Input**: `spec.md`, `plan.md`, `research.md`, `data-model.md`, `contracts/skill-bindings.md`,
`quickstart.md`

**Tests**: 本特性的 spec 明确要求自动化验证渐进加载、工具审计、原子迁移、五类一致性问题和软连接
逃逸；所有故事均按“先写失败测试，再实现”执行。

**Organization**: 任务按 User Story 分组。US1 是运行时 MVP；US2、US4 在共享基础完成后可独立推进；
US3 最后把迁移、启动恢复和运维诊断接入完整生命周期。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可与同阶段其它标记任务并行，修改不同文件且不依赖未完成任务。
- **[Story]**: 对应 `spec.md` 中的 US1/US2/US3/US4。

## Phase 1: Setup — 测试地基与旧草稿对齐

**Purpose**: 建立跨故事可复用的文件系统测试夹具，并把现有 pre-clarify 草稿显式纳入重构范围。

- [x] T001 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillWorkspaceFixture.java` 建立可创建临时 Agent、已安装 Skill、固定相对链接和损坏链接的共享测试夹具
- [x] T002 [P] 在 `oryxos-tool/src/test/java/io/oryxos/tool/sandbox/SandboxPathFixture.java` 建立外部目录、父链接、多跳链接、dangling 链接和链接环测试夹具
- [x] T003 [P] 在 `oryxos-core/src/test/java/io/oryxos/core/profile/ProfileLoaderTest.java`、`oryxos-core/src/test/java/io/oryxos/core/context/ContextLoaderTest.java`、`oryxos-core/src/test/java/io/oryxos/core/agent/AgentLifecycleServiceTest.java` 中移除旧 `Profile.skills` 与 Skill 正文预载夹具，保留 legacy 迁移专用样例

---

## Phase 2: Foundational — 所有故事的阻塞基础

**Purpose**: 固定元数据读取、真实路径投影和无 `Profile.skills` 的公共契约。

**⚠️ CRITICAL**: 本阶段完成前不得开始任何 User Story 实现。

### Tests

- [x] T004 [P] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillMetadataReaderTest.java` 添加仅解析 frontmatter、name/description/正文边界校验、目录名不一致和不可读文件的失败测试
- [x] T005 [P] 在 `oryxos-core/src/test/java/io/oryxos/core/fs/RealPathBoundaryTest.java` 添加存在目标、不存在普通目标、父链接、dangling、多跳、链接环和根自身为链接的投影失败测试
- [x] T006 [P] 在 `oryxos-core/src/test/java/io/oryxos/core/profile/ProfileLoaderTest.java` 添加运行时 Profile 不再产生 `skills` 字段且新建/更新可识别 legacy 顶层键的失败测试

### Implementation

- [x] T007 [P] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/BoundSkillDescriptor.java`、`BindingInspection.java`、`SkillBindingIssue.java`、`SkillReference.java`、`SkillArchive.java` 创建不可变共享模型
- [x] T008 [P] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillMetadataReader.java` 实现流式 frontmatter 读取与名称、描述、非空正文边界校验，不把正文保存进目录模型
- [x] T009 [P] 在 `oryxos-core/src/main/java/io/oryxos/core/fs/RealPathBoundary.java` 实现基于 `NOFOLLOW_LINKS` 最近存在节点的真实路径投影和根边界判断
- [x] T010 在 `oryxos-core/src/main/java/io/oryxos/core/profile/Profile.java` 与 `oryxos-core/src/main/java/io/oryxos/core/profile/ProfileLoader.java` 删除 `skills` 字段、访问器和解析分支，并让 legacy 检测留给迁移服务
- [x] T011 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillLoader.java` 与 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillStore.java` 对齐 InstalledSkill 元数据完整性、目录名一致性和安全目录入口

**Checkpoint**: 元数据可独立读取、真实路径可复用、Profile 不再承载绑定；所有故事可以开始。

---

## Phase 3: User Story 1 — Agent 按需使用已绑定 Skill (Priority: P1) 🎯 MVP

**Goal**: 每次 LLM 调用只加载当前 Agent 有效 Skill 的 name/description/本地入口，正文和资源只经
`read_file`/`shell` 按需进入后续 ReAct。

**Independent Test**: 两个有效绑定、一个未绑定和一个坏绑定并存；首次 prompt 只有有效元数据且无
正文。模型调用 `read_file` 后，正文只作为已审计 Tool Result 进入第二轮；两轮之间更新描述或解绑，
第二轮立即反映。

### Tests for User Story 1

- [x] T012 [P] [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/context/ProgressiveDisclosureTest.java` 添加零绑定无标题、稳定排序、仅 name/description/local absolute path、无正文/附属资源/未绑定项的失败测试
- [x] T013 [P] [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/agent/ReActLoopSkillDisclosureTest.java` 添加两轮 ReAct 集成测试，断言 `read_file` 结果与 `tool_invocations` 审计进入第二轮 history
- [x] T014 [P] [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/context/ContextLoaderTest.java` 添加两次 build 间修改 Skill 描述、解绑和修复链接后下一轮立即生效的无缓存测试

### Implementation for User Story 1

- [x] T015 [P] [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/AgentSkillBindingReader.java` 定义只读 `inspect(agentName)` 边界
- [x] T016 [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/AgentSkillBindingService.java` 实现当前 Agent 固定相对链接的实时发现、lexical/realpath 双校验和有效绑定排序
- [x] T017 [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/context/ContextLoader.java` 改为每次调用 Reader，只渲染三级加载说明、name、description 和 Agent 本地绝对 `SKILL.md` 路径
- [x] T018 [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/agent/PromptBuilder.java` 与 `oryxos-core/src/main/java/io/oryxos/core/agent/ReActLoop.java` 保持每次 provider 调用前重新 build，并明确相对资源以 Skill 入口目录解析的 prompt 契约
- [x] T019 [US1] 在 `oryxos-cli/src/main/java/io/oryxos/cli/OryxOsRuntime.java` 注入唯一 `AgentSkillBindingReader` 实例，移除 `ContextLoader(Path, SkillRegistry)` 兼容装配
- [x] T020 [US1] 运行 `oryxos-core/pom.xml` 的 `ProgressiveDisclosureTest,ContextLoaderTest,ReActLoopSkillDisclosureTest`，确认 US1 独立验收全绿

**Checkpoint**: US1 MVP 可独立演示，未使用 Skill 不占正文上下文，按需读取保留既有审计。

---

## Phase 4: User Story 2 — 管理公共 Skill 与 Agent 绑定 (Priority: P1)

**Goal**: 支持 catalog 查询、绑定 CRUD/原子替换、用户必选 + 作者建议、Agent 保存，以及无引用 Skill
完整归档；任何流程都不写 frontmatter `skills:` 或复制 Skill。

**Independent Test**: 用户选择一个必选 Skill，作者模型补充一个 catalog 内已安装 Skill；保存后两个
都是固定相对链接。两个 Agent 共享更新即时可见；解绑互不影响；活跃/归档引用阻止 Skill 归档，
全部解绑后完整目录进入 `archive/skills`。

### Tests for User Story 2

- [x] T021 [P] [US2] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/AgentSkillBindingServiceTest.java` 添加 bind/unbind 幂等、固定 target、普通文件冲突、原子 replace 回滚和 active/archive references 失败测试
- [x] T022 [P] [US2] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillArchiveServiceTest.java` 添加有引用拒绝、无引用完整归档、不覆盖历史和 `archive/skills` 保留名兼容测试
- [x] T023 [P] [US2] 在 `oryxos-core/src/test/java/io/oryxos/core/agent/AgentLifecycleSkillBindingTest.java` 添加直接创建、生成 sidecar、required ∪ suggested、列表外/未安装拒绝和全流程失败回滚测试
- [x] T024 [P] [US2] 在 `oryxos-web/src/test/java/io/oryxos/web/controller/AgentSkillBindingApiTest.java` 添加绑定查询、单项 CRUD、原子替换、AgentView 实时 skills 和保存 skillBindings 的 MockMvc 合同测试
- [x] T025 [P] [US2] 在 `oryxos-web/src/test/java/io/oryxos/web/controller/SkillCatalogApiTest.java` 与 `oryxos-web/src/test/java/io/oryxos/web/controller/SkillApiControllerTest.java` 添加 catalog public/private/installed 查询、503 fail-closed、409 结构化引用和归档响应合同测试

### Implementation for User Story 2

- [x] T026 [P] [US2] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillCatalog.java` 与 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillCatalogEntry.java` 定义已过滤外部候选 port 和 PUBLIC/PRIVATE/installed 元数据
- [x] T027 [US2] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/AgentSkillBindingService.java` 实现 bind、unbind、原子 replace、结构化 references 与 workspace 级共享临界区
- [x] T028 [US2] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillStore.java` 实现移动到 `.oryxos/archive/skills/<name>-<timestamp>` 的非覆盖归档，并兼容旧 `archive/skills/AGENT.md` 数据
- [x] T029 [US2] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillService.java` 将 delete 改为锁内引用检查 + archive，成功后再更新 Registry，并返回 `SkillArchive`
- [x] T030 [US2] 在 `oryxos-core/src/main/java/io/oryxos/core/agent/AgentStore.java` 保留平铺归档深度、处理 Agent 名 `skills`，并拒绝 `writeAll` 写入保留的 `skills/**` 普通文件
- [x] T031 [US2] 在 `oryxos-core/src/main/java/io/oryxos/core/agent/AgentLifecycleService.java` 删除 frontmatter Skill 生成逻辑，改为瞬时 `GeneratedAgentDraft` sidecar、catalog 交集校验和创建/保存/调度/绑定失败回滚
- [x] T032 [P] [US2] 在 `oryxos-web/src/main/java/io/oryxos/web/controller/dto/GeneratedFilesView.java`、`GenerateFilesRequest.java`、`SaveFilesRequest.java`、`CreateAgentRequest.java`、`AgentSkillBindingsView.java`、`SkillArchiveView.java` 创建最终 HTTP DTO
- [x] T033 [US2] 在 `oryxos-web/src/main/java/io/oryxos/web/controller/AgentApiController.java` 与 `oryxos-web/src/main/java/io/oryxos/web/controller/dto/AgentView.java` 接入绑定查询/单项 CRUD/原子替换、sidecar 保存和实时 skills 投影
- [x] T034 [US2] 在 `oryxos-web/src/main/java/io/oryxos/web/controller/SkillApiController.java` 与 `oryxos-web/src/main/java/io/oryxos/web/GlobalExceptionHandler.java` 接入 catalog 查询、SkillArchive 返回和 409 结构化引用冲突
- [x] T035 [US2] 在 `oryxos-web/src/main/frontend/src/App.vue` 改造 Skill catalog 筛选、required/建议标识、最终 skillBindings 保存、Agent 绑定 CRUD 和归档引用展示，删除所有 frontmatter/正文预载旧文案
- [x] T036 [US2] 运行 `oryxos-core/pom.xml` 与 `oryxos-web/pom.xml` 的 US2 core/MockMvc 测试，确认共享更新、生成保存和归档流程独立全绿

**Checkpoint**: US2 管理闭环成立，外部候选/生成草稿不是绑定真相源，Skill 删除可追溯。

---

## Phase 5: User Story 4 — 软连接不能绕过沙箱 (Priority: P1)

**Goal**: 合法工作区 Skill 链接可读；存在/不存在目标、Workspace API 和 Store 均不能借软连接访问或
写入工作区外，目录树不跟随链接递归。

**Independent Test**: 在允许根内创建指向根外的最终链接和父目录链接；read/write/download/store
全部在 IO 前拒绝且外部不变。合法 Skill 链接在允许根覆盖真实公共实体时放行，tree 只显示 link
叶节点并能终止链接环。

### Tests for User Story 4

- [x] T037 [P] [US4] 在 `oryxos-tool/src/test/java/io/oryxos/tool/sandbox/WhitelistSandboxTest.java` 添加存在读写、不存在写、dangling、多跳、链接环、合法 Skill 链接和最小白名单根测试矩阵
- [x] T038 [P] [US4] 在 `oryxos-web/src/test/java/io/oryxos/web/controller/WorkspaceApiControllerTest.java` 添加 file/download/write 越界链接拒绝、外部不变、link 叶节点和 cycle 终止测试
- [x] T039 [P] [US4] 在 `oryxos-core/src/test/java/io/oryxos/core/agent/AgentStoreTest.java` 与 `oryxos-core/src/test/java/io/oryxos/core/skill/SkillStoreTest.java` 添加 write/writeAll 经最终或中间链接逃逸时拒绝且普通写正常的测试

### Implementation for User Story 4

- [x] T040 [US4] 在 `oryxos-tool/src/main/java/io/oryxos/tool/sandbox/WhitelistSandbox.java` 使用 `RealPathBoundary` 规范白名单根并在每次 FILE_READ/FILE_WRITE 前投影目标真实路径
- [x] T041 [US4] 在 `oryxos-web/src/main/java/io/oryxos/web/controller/WorkspaceApiController.java` 对 file/download/write 使用真实路径边界，拒绝经 `agents/*/skills/**` 写共享 Skill，并让 tree 不递归软连接
- [x] T042 [P] [US4] 在 `oryxos-web/src/main/java/io/oryxos/web/controller/dto/FileNode.java` 与 `oryxos-web/src/main/frontend/src/App.vue` 增加 link 叶节点展示及 dangling/escaped 状态
- [x] T043 [US4] 在 `oryxos-core/src/main/java/io/oryxos/core/agent/AgentStore.java` 与 `oryxos-core/src/main/java/io/oryxos/core/skill/SkillStore.java` 对目录和每个写目标复用 `RealPathBoundary`
- [x] T044 [US4] 在 `oryxos-tool/src/test/java/io/oryxos/tool/builtin/FileToolsTest.java` 增加所有文件写类 Tool 在 sandbox 拒绝后零 IO 的集成回归，并运行 `oryxos-tool/pom.xml`、`oryxos-web/pom.xml` 的 US4 测试

**Checkpoint**: US4 安全边界独立全绿；字符串 normalize 不再作为任何新链接入口的最终安全判断。

---

## Phase 6: User Story 3 — 发现损坏、残留与旧配置 (Priority: P2)

**Goal**: 启动、CRUD 和 Agent 生命周期能分类报告五类问题；旧 frontmatter 单 Agent 原子迁移，坏
绑定不进入 prompt且不阻断其它 Agent。

**Independent Test**: 构造五类问题与两个旧 Agent（一个合法、一个含不存在 Skill），启动后合法项
完成幂等迁移，失败 Agent 原文件字节和链接集合不变；全局问题 API 分类完整，合法 Agent 正常运行。

### Tests for User Story 3

- [x] T045 [P] [US3] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/AgentSkillReconciliationTest.java` 添加 dangling、escaped、invalid-target、name-mismatch、stale-reference 及归档合法链接非 stale 的失败测试
- [x] T046 [P] [US3] 在 `oryxos-core/src/test/java/io/oryxos/core/skill/AgentSkillMigrationServiceTest.java` 添加成功仅移除顶层键、原文保真、已有链接并集、重复启动幂等、非法引用零变更和 IO/atomic-move 回滚测试
- [x] T047 [P] [US3] 在 `oryxos-cli/src/test/java/io/oryxos/cli/AgentSkillStartupOrderTest.java` 添加内置 Skill 播种先于迁移、迁移先于 Profile/Watcher、单 Agent 失败不阻断启动的装配测试
- [x] T048 [P] [US3] 在 `oryxos-web/src/test/java/io/oryxos/web/controller/SkillBindingIssuesApiTest.java` 添加全局 issues 稳定排序、活跃/归档状态和无 Profile stale 条目的 MockMvc 合同测试

### Implementation for User Story 3

- [x] T049 [US3] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/AgentSkillBindingService.java` 完成 active/archive 全量 reconcile、五类分类、日志消毒和排除 `archive/skills` 命名空间
- [x] T050 [P] [US3] 在 `oryxos-core/src/main/java/io/oryxos/core/agent/AgentMarkdown.java` 实现保留其它 frontmatter/正文格式且只移除顶层 `skills` 的文本变换 helper
- [x] T051 [US3] 在 `oryxos-core/src/main/java/io/oryxos/core/skill/AgentSkillMigrationService.java` 实现全量预校验、临时链接、AGENT.md 最后原子提交、异常回滚和崩溃后幂等收敛
- [x] T052 [US3] 在 `oryxos-cli/src/main/java/io/oryxos/cli/OryxOsRuntime.java` 增加 `AgentSkillStartupReport` 显式依赖链，按 seed → migrate → reconcile → Profile/调度 → Watcher 顺序启动
- [x] T053 [US3] 在 `oryxos-core/src/main/java/io/oryxos/core/agent/AgentLifecycleService.java` 的 register/save/archive/delete 后触发协调检查，并拒绝运行期新写入 legacy `skills:`
- [x] T054 [US3] 在 `oryxos-web/src/main/java/io/oryxos/web/controller/SkillApiController.java`、`oryxos-web/src/main/java/io/oryxos/web/controller/dto/SkillBindingIssueView.java` 与 `oryxos-web/src/main/frontend/src/App.vue` 增加全局问题查询和可读分类展示
- [x] T055 [US3] 运行 `oryxos-core/pom.xml`、`oryxos-cli/pom.xml` 与 `oryxos-web/pom.xml` 的迁移/协调/启动测试，确认 US3 独立验收全绿

**Checkpoint**: 五类问题可观测，旧模型被原子收敛，运行期只认软连接。

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: 同步最终术语、运行全部质量门禁，并按 quickstart 做端到端验收。

- [x] T056 [P] 在 `README.md`、`CLAUDE.md`、`docs/DemandAnalysis.md`、`docs/TechnicalSolution.md` 与 `.specify/memory/constitution.md` 同步三级加载、catalog sidecar、legacy 迁移和 Skill 归档最终语义
- [x] T057 [P] 在 `specs/012-agent-skill-links/quickstart.md` 逐项补齐实际命令/响应差异并记录所有场景的最终验证结果
- [x] T058 在 `oryxos-web/src/main/frontend/package.json` 执行 `npm run build`，修复 `oryxos-web/src/main/frontend/src/App.vue` 的编译或类型问题
- [x] T059 在根 `pom.xml` 执行 `mvn test`，修复全部模块回归并确认既有 Workspace tree 测试不再依赖固定 children 索引
- [x] T060 在根 `pom.xml` 执行 `mvn verify`，通过 Spotless、P3C、Checkstyle、SpotBugs/Find Security Bugs 与依赖检查门禁
- [x] T061 按 `specs/012-agent-skill-links/quickstart.md` 完成三级 prompt、作者建议、legacy 迁移、活跃/归档引用、完整 Skill 归档和真实路径逃逸的端到端验收
- [x] T062 在 `specs/012-agent-skill-links/tasks.md` 标记已完成任务，并对整个工作树执行 `git diff --check`、占位符扫描和最终 constitution compliance 复核

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 Setup**: 可立即开始。
- **Phase 2 Foundational**: 依赖 Phase 1，阻塞所有 User Story。
- **US1 / Phase 3**: 依赖 Foundational；先完成，提供 runtime binding reader 和 MVP。
- **US2 / Phase 4**: 依赖 Foundational，并复用 US1 的 binding reader/service；完成管理、生成和归档。
- **US4 / Phase 5**: 依赖 Foundational 的 RealPathBoundary；可与 US1/US2 的非 Store 文件并行，但合并
  AgentStore/SkillStore 前须协调 T030/T028 与 T043。
- **US3 / Phase 6**: 依赖 US1 的 inspect、US2 的写协调/归档和 US4 的安全写入口；最后接入迁移与启动。
- **Polish / Phase 7**: 依赖所有计划交付的 User Story。

### User Story Dependency Graph

```text
Setup → Foundational → US1 (MVP)
                    ├→ US2 ─┐
                    └→ US4 ─┼→ US3 → Polish
                            ┘
```

### Within Each User Story

- 对应 Tests 任务必须先写并确认失败。
- 数据模型/接口先于服务实现，服务先于 Controller/UI。
- 所有 I/O 变更先全量验证，再执行并覆盖失败回滚。
- 每个 Checkpoint 的目标测试全绿后才能进入依赖它的后续故事。

## Parallel Opportunities

### User Story 1

```text
并行：T012 ProgressiveDisclosureTest
并行：T013 ReActLoopSkillDisclosureTest
并行：T014 ContextLoader 无缓存测试
随后：T015 → T016 → T017/T018 → T019 → T020
```

### User Story 2

```text
并行：T021 绑定服务测试、T022 归档测试、T023 生命周期测试、T024/T025 Web 合同测试
并行：T026 catalog port、T032 HTTP DTO
随后：T027 → T028/T030 → T029/T031 → T033/T034 → T035 → T036
```

### User Story 4

```text
并行：T037 Sandbox 测试、T038 Workspace 测试、T039 Store 测试
随后：T040；并行推进 T041/T042 与 T043；最后 T044
```

### User Story 3

```text
并行：T045 reconcile 测试、T046 迁移测试、T047 启动顺序测试、T048 API 合同测试
并行：T049 reconcile 与 T050 Markdown helper
随后：T051 → T052/T053 → T054 → T055
```

## Implementation Strategy

### MVP First

1. 完成 Phase 1–2。
2. 完成 US1（T012–T020）。
3. 停止并独立验证：每次 prompt 只有元数据；`read_file` 后正文才进入已审计 history。
4. US1 通过后再引入管理 CRUD、归档和迁移，避免一次改变所有运行时入口。

### Incremental Delivery

1. **MVP**: US1 — 三级渐进加载与实时软连接发现。
2. **Management**: US2 — catalog、生成 sidecar、绑定 CRUD、Skill 归档。
3. **Security**: US4 — 所有文件入口的真实路径边界。
4. **Operations**: US3 — legacy 原子迁移、五类问题与启动恢复。
5. **Release**: 文档、前端、全量质量门禁、quickstart。

## Notes

- 所有任务初始保持 `[ ]`；现有 pre-clarify 代码草稿不自动视为已完成。
- `[P]` 仅表示文件和依赖允许并行；共享 `AgentStore`/`SkillStore` 修改必须在合并前串行协调。
- 不新增 `use_skill`、绑定数据库表、自动远程安装或 OryxOS Skill ACL。
- 外部 catalog unavailable 时 fail closed；具体重试/缓存协议不在本特性范围。
- Java NIO 下同 OS 用户恶意进程的校验后换链 TOCTOU 不在当前威胁模型，确定性链接逃逸必须全拦。
