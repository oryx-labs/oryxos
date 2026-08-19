# Tasks: 知识库（Knowledge Base）

**Input**: `spec.md`、`plan.md`、`research.md`（D1~D11）、`data-model.md`、
`contracts/knowledge-spi.md`、`contracts/rest-api.md`、`quickstart.md`

**Tests**: spec 要求以参数化契约测试钉死 8 条行为契约（contracts/knowledge-spi.md §2），
且 SC-004 要求 mock 环境 CI 可稳定断言；所有故事按「先写失败测试，再实现」执行。

**Organization**: 任务按 User Story 分组。Phase 1~3 是共享地基（契约 → 存储 → 本地后端
引擎）；US1 是运行时 MVP；US2/US3 管理面、US4 GitOps、US7 看板在地基完成后可并行推进；
US5 远程桩与 US6 无 key 自测最后收口契约与 CI。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可与同阶段其它标记任务并行（不同文件、无未完成依赖）。
- **[Story]**: 对应 `spec.md` 的 US1~US7。

## Phase 1: Setup — 模块骨架与测试夹具

**Purpose**: 新建 `oryxos-knowledge` 模块（宪法停点 1）并搭好跨故事测试地基。

- [x] T001 新建 `oryxos-knowledge/pom.xml`（对照 `oryxos-memory`：依赖 core + storage +
      spring-ai-model 取 `@Tool`；新增 Apache PDFBox），并把模块加入根 `pom.xml`、
      `oryxos-boot/pom.xml`、`oryxos-cli/pom.xml` 依赖聚合
- [x] T002 [P] 在 `oryxos-core/src/test/java/io/oryxos/core/knowledge/KnowledgeWorkspaceFixture.java`
      建共享测试夹具：临时工作区内创建知识库目录（KNOWLEDGE.md 清单 + md/txt/PDF 文档）、
      Agent 目录、合法/dangling/escaped/name-mismatch 绑定软连接

---

## Phase 2: Foundational A — 契约与存储（阻塞所有故事）

**Purpose**: 宪法停点 2/3 落地——core 契约面 + schema.sql 两表 + 存储实体。

**⚠️ CRITICAL**: 本阶段完成前不得开始任何 User Story 实现。

### Tests

- [x] T003 [P] 在 `oryxos-core/src/test/java/io/oryxos/core/knowledge/KnowledgeManifestTest.java`
      添加清单解析失败测试：frontmatter name/description 校验、name 与目录名不一致、
      backend 缺省 local、远程 connection 的 `${ENV_VAR}` 占位不解析明文
- [x] T004 [P] 在 `oryxos-core/src/test/java/io/oryxos/core/knowledge/KnowledgeBindingServiceTest.java`
      添加绑定失败测试：bind/unbind/replace/inspect/references/reconcile 全操作 +
      四类非法态检测 + 真实路径越界拒绝（复用 `RealPathBoundary`）

### Implementation

- [x] T005 [P] 在 `oryxos-core/src/main/java/io/oryxos/core/knowledge/model/` 创建不可变值对象：
      `KnowledgeQuery`、`KnowledgeHit`、`Citation`、`KnowledgeBaseInfo`、`DocumentStatus`、
      `KnowledgeCapabilities`（字段按 data-model.md §4）
- [x] T006 [P] 在 `oryxos-core/src/main/java/io/oryxos/core/knowledge/` 创建契约接口：
      `KnowledgeRetriever`（必选）、`KnowledgeAdmin`（可选）、`KnowledgeBackend`、
      `KnowledgeBackendRegistry`（按名注册 + localDefault）、`KnowledgeService`（门面），
      形状按 contracts/knowledge-spi.md §1，全同步签名
- [x] T007 在 `oryxos-core/src/main/java/io/oryxos/core/knowledge/KnowledgeManifest.java`
      实现 KNOWLEDGE.md frontmatter 读取与合法性校验（T003 转绿）
- [x] T008 在 `oryxos-core/src/main/java/io/oryxos/core/knowledge/KnowledgeBindingService.java`
      照 `AgentSkillBindingService` 范式实现绑定 CRUD 与巡检（T004 转绿）；被引用删除抛
      `KnowledgeReferencedException`
- [x] T009 [P] 在 `oryxos-storage/src/main/resources/schema.sql` 末尾追加
      `knowledge_documents` / `knowledge_chunks` DDL（data-model.md §2：中文块注释 +
      `IF NOT EXISTS` + `idx_` 索引 + generation 双缓冲列 + dim/embedding_model 列）
- [x] T010 [P] 在 `oryxos-storage/src/main/java/io/oryxos/storage/` 创建
      `KnowledgeDocumentEntity.java`、`KnowledgeChunkEntity.java`、
      `KnowledgeDocumentRepository.java`、`KnowledgeChunkRepository.java`（平铺包，随现状）

**Checkpoint**: 契约面可编译、绑定可建可检、两表可建——引擎与故事实现可以开始。

---

## Phase 3: Foundational B — 本地后端引擎（阻塞所有故事）

**Purpose**: 第一个插件 `LocalKnowledgeBackend`：解析 → 切分 → 向量化 → 存取 → 检索流水线。

### Tests

- [x] T011 [P] 在 `oryxos-knowledge/src/test/java/io/oryxos/knowledge/index/DocumentParserTest.java`
      添加解析失败测试：md/txt 片段序号、文本型 PDF 页码出处、扫描件（无文本层）拒绝、
      空文档跳过、>10MB 拒绝、二进制忽略告警
- [x] T012 [P] 在 `oryxos-knowledge/src/test/java/io/oryxos/knowledge/index/ChunkerTest.java`
      添加切分失败测试：标题边界优先 + 长度上限、不跨文档、位置可回溯（行为契约 2）
- [x] T013 [P] 在 `oryxos-provider/src/test/java/io/oryxos/provider/MockEmbeddingModelTest.java`
      添加确定性测试：同文本恒同向量、单位向量、不同文本可区分（行为契约 6 / SC-004）
- [x] T014 [P] 在 `oryxos-knowledge/src/test/java/io/oryxos/knowledge/retrieve/RetrievalPipelineTest.java`
      添加流水线失败测试：双路召回并行、RRF 名次融合、精排槽位直通、embedding 不可用走
      关键词降级并标注（行为契约 4）、维度/模型不一致拒绝混比（行为契约 5）

### Implementation

- [x] T015 [P] 在 `oryxos-provider/src/main/java/io/oryxos/provider/ProviderEmbeddingModelFactory.java`
      复用 `OpenAiApi` 构建链（stripTrailingV1/HTTP1.1/超时工厂）按名构建 EmbeddingModel；
      `knowledge.embedding.provider` 未配置 ⇒ 显式不可用（可读报错，不静默回退，plan 停点 3）
- [x] T016 [P] 在 `oryxos-provider/src/main/java/io/oryxos/provider/MockEmbeddingModel.java`
      实现文本哈希播种的确定性伪随机单位向量（T013 转绿）
- [x] T017 [P] 在 `oryxos-knowledge/src/main/java/io/oryxos/knowledge/index/` 实现
      `DocumentParser` SPI + `MarkdownParser`/`TextParser`/`PdfParser`（PDFBox）+ `Chunker`
      （T011/T012 转绿）
- [x] T018 在 `oryxos-knowledge/src/main/java/io/oryxos/knowledge/store/SqliteVectorIndexStore.java`
      实现 chunk/向量 BLOB 存取与按库全量加载（float32 编解码；接口形状不带 knowledge
      语义，FR-016；配置键 `knowledge.store` 默认 sqlite）
- [x] T019 在 `oryxos-knowledge/src/main/java/io/oryxos/knowledge/retrieve/RetrievalPipeline.java`
      实现双路召回（纯 Java 余弦暴力 ∥ 关键词 LIKE）→ RRF 融合 → 精排槽位（T014 转绿）
- [x] T020 在 `oryxos-knowledge/src/main/java/io/oryxos/knowledge/index/KnowledgeIndexService.java`
      实现导入流水线：SHA-256 指纹变更检测、文档状态机（PENDING→INDEXING→READY/FAILED
      + 可读失败原因）、虚拟线程后台推进、失败可重试不静默丢弃
- [x] T021 在 `oryxos-knowledge/src/main/java/io/oryxos/knowledge/LocalKnowledgeBackend.java`
      组装为全能力后端插件（capabilities 全 true、rerank false）并实现 `KnowledgeAdmin`
- [x] T022 在 `oryxos-knowledge/src/test/java/io/oryxos/knowledge/contract/KnowledgeBackendContractTest.java`
      建参数化契约测试骨架：8 条行为契约逐条断言，先只挂 `LocalKnowledgeBackend`
      （US5 阶段挂远程桩），mock 向量下全部转绿

**Checkpoint**: 手工构造目录可完成「导入 → 索引 → 检索命中带出处」，全部契约测试绿。

---

## Phase 4: User Story 1 — 终端用户带出处回答 (Priority: P1) 🎯 MVP

**Goal**: Agent 自主检索绑定库、按出处跟读原文、回答带出处；未绑定零注入零干扰。

**Independent Test**: 手工构造知识库目录与绑定链接（不依赖管理台），CLI chat / REST 对话验证
（spec US1 场景 1~6）。

### Tests

- [x] T023 [P] [US1] 在 `oryxos-knowledge/src/test/java/io/oryxos/knowledge/builtin/KnowledgeToolsTest.java`
      添加失败测试：schema 两必一选（query/limit/knowledge_base）、绑定范围圈定、多库聚合
      全局 top-K、限定未绑定库可读错误、零绑定可读错误、结果 JSON 含埋点字段（FR-022）
- [x] T024 [P] [US1] 在 `oryxos-core/src/test/java/io/oryxos/core/context/ContextLoaderKnowledgeTest.java`
      添加失败测试：每轮注入绑定库 name+description+检索指引、零绑定零注入、正文永不预载

### Implementation

- [x] T025 [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/knowledge/DefaultKnowledgeService.java`
      实现门面：经 `KnowledgeBindingService` 圈定绑定库 → 逐库路由后端 → 跨库融合取全局
      top-K（Clarify-Q2）
- [x] T026 [US1] 在 `oryxos-knowledge/src/main/java/io/oryxos/knowledge/builtin/KnowledgeTools.java`
      实现 `@Tool retrieve_knowledge`（经 `ToolExecutionContext` 取 agentName；结果为
      contracts §3 的结构化 JSON 埋点载体）（T023 转绿）
- [x] T027 [US1] 在 `oryxos-core/src/main/java/io/oryxos/core/context/ContextLoader.java`
      新增 `appendKnowledge()`（对照 `appendSkills()`；按 `profile.tools()` 含
      `retrieve_knowledge` 联动）（T024 转绿）
- [x] T028 [US1] 在 `oryxos-cli/src/main/java/io/oryxos/cli/OryxOsRuntime.java` 装配：
      backend registry、`LocalKnowledgeBackend`、`KnowledgeService`、
      `registry.registerAnnotated(new KnowledgeTools(...))`，并同步
      `OryxToolContractTest` 参数化注册面
- [x] T029 [US1] 本地库文档目录自动纳入 `read_file` 白名单（FR-017）：沿用 `oryxos.root`
      换根自动纳入机制 + `RealPathBoundary` 校验；远程命中标注不可跟读；补
      `oryxos-tool/src/test/java/io/oryxos/tool/sandbox/` 白名单联动测试
- [x] T030 [US1] 端到端验证 quickstart §C：命中带出处 / 原文跟读 / 无关问题不干扰 /
      检索入 `tool_invocations` 审计（US1 场景 6），修复暴露的问题

**Checkpoint**: MVP 达成——mock 环境下 US1 六个验收场景全部通过。

---

## Phase 5: User Story 2 — 管理台全生命周期 (Priority: P1)

**Goal**: 非技术管理员在管理台完成建库→传文档→绑定→验证→维护→删除闭环。

**Independent Test**: 仅通过管理台完成闭环（spec US2 场景 1~6），计时验收 SC-001（≤5 分钟）。

### Tests

- [x] T031 [P] [US2] 在 `oryxos-web/src/test/java/io/oryxos/web/controller/KnowledgeApiControllerTest.java`
      添加 MockMvc 失败测试：CRUD、两段式上传（不支持类型/扫描件/超限 4xx 当场拒绝）、
      状态查询、重建、重名 409、被引用删除 409 + Agent 名单、能力门禁 400
      （contracts/rest-api.md 全表）

### Implementation

- [x] T032 [US2] 在 `oryxos-web/src/main/java/io/oryxos/web/controller/KnowledgeApiController.java`
      + `controller/dto/` record DTO 实现 REST 全生命周期端点（T031 转绿）；
      `GlobalExceptionHandler` 新增 `KnowledgeReferencedException` → 409
- [x] T033 [US2] 在 `oryxos-knowledge/.../index/KnowledgeIndexService.java` 补双缓冲重建
      （FR-024）：generation+1 后台构建、同步临界区原子切换、失败保留旧代与原因；
      补重建期间并发检索不中断的测试
- [x] T034 [US2] 在 `oryxos-web/src/main/frontend/src/App.vue` 落地知识库页（TOP_NAV 占位
      已存在）：列表（名称/描述/后端/文档数/片段数/状态）、详情（文档清单 + 单文档删除 +
      重建 + 上传）、创建/删除；操作按钮按能力集渲染（FR-009）；`npm run build` 通过

**Checkpoint**: US2 六个验收场景通过，SC-001 计时达标。

---

## Phase 6: User Story 3 — 创建 Agent 三路径关联 (Priority: P2)

**Goal**: 新建表单多选、一句话生成绑定建议、编辑增改，三路径绑定一致。

**Independent Test**: 已有知识库前提下分别走三条路径，验证绑定生效且三界面一致（SC-007）。

### Tests

- [x] T035 [P] [US3] 在 `oryxos-web/src/test/java/io/oryxos/web/controller/AgentApiControllerTest.java`
      补绑定三件套失败测试（GET/PUT/DELETE `/agents/{name}/knowledge`，非法链接 4xx）与
      Agent 创建/编辑请求 `knowledgeBindings` 字段测试

### Implementation

- [x] T036 [US3] 在 `oryxos-web/src/main/java/io/oryxos/web/controller/AgentApiController.java`
      实现绑定三件套（照 skills 三件套先例）+ 创建/编辑端点接受 `knowledgeBindings`
      并经 `KnowledgeBindingService` 落软连接（T035 转绿）
- [x] T037 [US3] 「一句话生成 Agent」起草上下文注入现有知识库名单（name+description，与
      工具/渠道候选同等待遇，FR-018）；草稿含 `knowledgeBindings` 建议、保存时后端重新
      校验（照 Skill 建议流程：草稿不是持久绑定索引）
- [x] T038 [US3] `App.vue` Agent 详情页绑定管理视图 + 新建/编辑表单知识库多选（SC-007
      三界面一致）

**Checkpoint**: US3 三条路径验收通过，SC-008 留人工评审。

---

## Phase 7: User Story 4 — GitOps 热加载 + CLI (Priority: P2)

**Goal**: 纯文件系统上线知识库，运行中自动发现索引，CLI 可查。

**Independent Test**: 服务运行中纯文件操作 + `oryxos knowledge list`（spec US4 场景 1~5，
SC-006 30 秒窗口）。

### Tests

- [x] T039 [P] [US4] 在 `oryxos-knowledge/src/test/java/io/oryxos/knowledge/watch/KnowledgeWatcherTest.java`
      添加失败测试：新目录发现、文档改删触发重索引、非法目录不注册 + 告警不影响他库、
      启动 reconcile 对账

### Implementation

- [x] T040 [US4] 在 `oryxos-knowledge/src/main/java/io/oryxos/knowledge/watch/KnowledgeWatcher.java`
      照 `WorkspaceWatcher` 非递归补挂骨架实现热加载 + 启动对账（T039 转绿），接入
      `OryxOsRuntime` 启动顺序
- [x] T041 [P] [US4] 在 `oryxos-cli/src/main/java/io/oryxos/cli/command/KnowledgeCommand.java`
      实现 `oryxos knowledge list`（与 `provider list` 同族输出）；`InitCommand.DIRS`
      加 `"knowledge"`；注册到 `OryxOsCli` 子命令表

**Checkpoint**: US4 五个验收场景通过（含停服改目录后对账）。

---

## Phase 8: User Story 7 — 使用效果看板 (Priority: P2)

**Goal**: 管理台回答「这个库被用得怎么样、该补什么文档」，指标与审计可核对。

**Independent Test**: 制造命中/零结果/降级三类调用后看板核对（spec US7 场景 1~3，SC-009）。

### Tests

- [x] T042 [P] [US7] 在 `KnowledgeApiControllerTest.java` 补 metrics 端点失败测试：
      检索次数/零结果率/降级率/命中文档分布/出处引用率、时间过滤、零结果查询原文列表、
      与 `tool_invocations` 数据一致

### Implementation

- [x] T043 [US7] 在 `KnowledgeApiController` 实现 `GET /knowledge/{name}/metrics`：只聚合
      `tool_invocations`（FR-023 不另建统计路径）；出处引用率关联会话最终回答文本近似计算
      （T042 转绿）
- [x] T044 [US7] `App.vue` 库详情使用看板视图（指标卡 + 时间过滤 + 零结果列表）

**Checkpoint**: US7 三个验收场景通过，看板逐项与审计 SQL 核对一致。

---

## Phase 9: User Story 5 — 远程后端桩与能力门禁 (Priority: P3)

**Goal**: 用「仅检索能力」测试桩证明插件契约：三同（同工具/同出处契约/同审计）+ 门禁。

**Independent Test**: spec US5 场景 1~3 + SC-011 逐项核验。

- [x] T045 [P] [US5] 在 `oryxos-knowledge/src/test/java/io/oryxos/knowledge/contract/StubRemoteBackend.java`
      实现测试桩（仅声明 retrieve；可配置返回无出处命中、模拟不可达），挂入 T022 参数化
      契约测试并全部转绿
- [x] T046 [US5] 端到端核验：`backend: stub` 清单库绑定检索三同、缺出处显式标注「出处
      不可用」且不可跟读、管理端点 400 门禁、管理台不渲染入口、不可达可读错误入审计
      （quickstart §E）

**Checkpoint**: SC-011 三同逐项通过。

---

## Phase 10: User Story 6 — 无 key 环境 CI 自测 (Priority: P3)

**Goal**: mock provider 下 REST 全流程确定可重复，CI 稳定断言。

- [x] T047 [US6] 在 `oryxos-boot`（或 `oryxos-web`）新增端到端集成测试：仅 mock provider
      走通 quickstart §A 全流程（建库→上传→轮询 READY→绑定→invoke 命中带出处→审计核对→
      引用保护 409→解绑删除），重复执行结果一致（SC-004）

**Checkpoint**: CI 无凭证全绿。

---

## Phase 11: Polish — 文档同步与全量验收

- [x] T048 [P] 同步 `CLAUDE.md` 模块表 + `docs/TechnicalSolution.md` §10 新增
      `oryxos-knowledge` 模块（宪法停点 1 的文档义务）；`website/zh/docs/architecture.md:80`
      「知识库（占位）」落地
- [x] T049 [P] `README.md` 能力清单与 CLI 命令表补知识库；`config/application.yml.example`
      （如有）补 `knowledge.*` 配置段示例
- [x] T050 跑满 quickstart §A~G 全量验收 + `mvn verify` 全绿（Spotless/P3C/Checkstyle/
      SpotBugs/OWASP）+ 前端 `npm run build`；SC-001~SC-011 逐条勾验

---

## Dependencies & Execution Order

- **Phase 1 → 2 → 3 严格串行**（模块骨架 → 契约存储 → 引擎），是所有故事的地基。
- **Phase 4（US1）** 依赖 Phase 3；**Phase 5（US2）** 依赖 Phase 3 + T032 依赖 T025 门面。
- **Phase 6（US3）/ 7（US4）/ 8（US7）** 依赖 Phase 4~5 的服务与端点，三者互相独立可并行。
- **Phase 9（US5）** 依赖 T022 契约测试骨架与 Phase 5 能力门禁；**Phase 10（US6）** 依赖
  Phase 4~5 全链路；**Phase 11** 最后。

### 对应实现 PR 切分（plan「Delivery Order」）

| PR | 内容 | 任务 |
|----|------|------|
| impl-1 契约骨架 | Phase 1 + Phase 2（含 CLAUDE.md 模块表同步提前到此 PR） | T001~T010, T048 前半 |
| impl-2 本地后端 | Phase 3 | T011~T022 |
| impl-3 运行时 MVP | Phase 4 | T023~T030 |
| impl-4 管理面 | Phase 5 + Phase 6 | T031~T038 |
| impl-5 GitOps | Phase 7 | T039~T041 |
| impl-6 观测与收口 | Phase 8~11 | T042~T050 |

每个 PR 合并前：新增测试全绿 + `mvn verify` 通过 + PR 描述标注对应 FR/SC 编号。
