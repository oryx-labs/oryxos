# Implementation Plan: 知识库（Knowledge Base）

**Branch**: `014-knowledge-base` | **Date**: 2026-08-19 | **Spec**: [spec.md](spec.md)

**Input**: 六轮修订后冻结的 spec、[research.md](research.md)（决策 D1~D11）、
[traceability.md](traceability.md)（FR↔US↔SC 矩阵）、Clarify 三问三答（2026-08-18）。

## Summary

「知识」以底座级标准操作契约定义——检索必选（`KnowledgeRetriever`）、管理逐项可选
（`KnowledgeAdmin`）、能力显式声明（`KnowledgeCapabilities`），知识库实现以插件经
`KnowledgeBackendRegistry` 按名挂载。v1 交付第一个插件：内置本地后端——文档
（markdown / 纯文本 / 文本型 PDF）切分向量化入 SQLite（BLOB 向量 + 纯 Java 余弦），
检索走双路召回（向量 ∥ 关键词）+ RRF 名次融合，精排只留能力槽位；`retrieve_knowledge`
工具带硬契约出处，本地库目录自动入 `read_file` 白名单支撑原文跟读。Agent 绑定复用
Skill 软连接范式（`agents/<a>/knowledge/<kb>` 相对链接为唯一真相源）。五界面同步落地：
对话出处、管理台全生命周期 + 使用看板、文件系统 GitOps 热加载、`oryxos knowledge list`、
REST 全量端点。mock provider 提供确定性向量，无 key 环境 CI 可完整断言。

## Technical Context

**Language/Version**: Java 21（虚拟线程）；管理台 Vue 3 + Vite

**Primary Dependencies**: Spring Boot 3.x、Spring MVC、SnakeYAML；
`spring-ai-openai:1.1.8` jar 已含 `OpenAiEmbeddingModel`（零新增，D2 实测）；
**Apache PDFBox 为唯一新增 Maven 依赖**（文本型 PDF 解析，D11）

**Storage**: SQLite 新增 `knowledge_documents` / `knowledge_chunks`（向量存 BLOB），
`schema.sql` 为唯一 DDL 权威（ddl-auto=none）；文件系统 `.oryxos/knowledge/<库名>/`
（实体）与 `.oryxos/agents/<Agent>/knowledge/<库名>`（绑定软连接）

**Testing**: JUnit 5、Mockito、Spring MockMvc；行为契约测试参数化覆盖各后端
（本地后端 + 仅声明检索能力的远程测试桩），钉死 D8 六条行为契约

**Target Platform**: 支持 Java NIO 符号链接的 Linux/macOS 服务端，单 fat JAR

**Project Type**: Maven 多模块企业单体 + 内嵌 Vue 管理台

**Performance Goals**: 单库万级 chunk、1024 维下单次检索 ≤ 1s（SC-002）；暴力余弦
扫描实测几十毫秒量级（D1）；热加载 30 秒窗口（SC-006）

**Constraints**: 全程同步阻塞（索引后台推进用虚拟线程执行器跑同步代码，不引入
Reactor / CompletableFuture 编程模型）；零新外部服务（单二进制交付不变）；出处为
硬契约；重建双缓冲、上传两段式（Clarify 拍板）

**Scale/Scope**: 单库万级片段、全站十万级以内（超出由可插拔后端换 pgvector 承接）；
涉及 `oryxos-core`、`oryxos-provider`、`oryxos-storage`、`oryxos-web`、`oryxos-cli`
与**新建 `oryxos-knowledge` 模块**

## Constitution Check

### 设计前门禁

- **I 自实现 ReAct Loop**: PASS。`retrieve_knowledge` 是普通 `OryxTool`，经既有
  `ToolExecutor` 调度；`ReActLoop` 零改动。检索精度外环（query 改写 + 按出处跟读）
  是模型在既有循环里的自主行为，不新增循环逻辑（D10）。
- **II Spring AI 使用边界**: PASS。embedding 走 `OpenAiEmbeddingModel` 手动调用
  （协议转换），复用 ChatModel 同款 `OpenAiApi` 构建链，不启用任何自动装配；
  `@Tool` 注解仅用于 `KnowledgeTools` 的 schema 生成。
- **III Provider 显式映射**: PASS。embedding provider 按名引用现有 Provider 注册表
  （`knowledge.embedding.provider`），不另建凭证；`KnowledgeBackendRegistry` 按名
  显式注册后端插件，同款哲学（D9 规避 AgentScope 无发现机制之坑）。
- **IV 目录 Agent + 软连接绑定 + 渐进披露**: PASS。绑定唯一真相源是
  `agents/<a>/knowledge/<kb>` 相对软连接，frontmatter 不声明知识库（FR-002）；
  `ContextLoader.appendKnowledge()` 每轮只注入绑定库的 name + description + 检索
  指引，零绑定零注入，正文永不预载（FR-005）。
- **V 审计 Day One**: PASS。`retrieve_knowledge` 经 `ToolExecutor` 自动落
  `tool_invocations`；FR-022 命中明细（库/文档/片段/分数 + 零结果/降级标记 + 查询
  原文）作为工具结果结构化落同一条审计记录；embedding 调用落 `llm_calls`
  （provider/model/token/耗时字段同构，completion_tokens 记 0）。
- **VI 真实路径沙箱**: PASS。本地库文档目录自动纳入 `read_file` 白名单沿用
  `oryxos.root` 换根同款机制并经真实路径校验（FR-017）；绑定链接复用
  `RealPathBoundary`：拒绝绝对链接、越界链接、名称不一致（FR-002）；远程后端凭证
  走环境变量引用，清单不落明文（FR-015）。
- **VII 同步执行**: PASS。全部契约为同步签名（D9 规避 AgentScope 全 Mono 之坑）；
  两段式上传的切分/向量化由虚拟线程执行器推进同步代码；双缓冲原子切换在同步临界区
  完成。
- **VIII 目录配置与状态外置**: PASS。知识库 = 目录（`KNOWLEDGE.md` 清单 + 文档），
  绑定 = 软连接，索引状态外置 SQLite；表结构手工 `schema.sql`，不用 Hibernate 迁移。

### 宪法停点声明（三项，按宪法「技术栈与架构约束」在 plan 中声明）

1. **新建模块 `oryxos-knowledge`**（依赖 core + storage，pom 对照 oryxos-memory）。
   理由（D3）：契约被 core 的 `ContextLoader`/`PromptBuilder` 消费，实现放 core 违反
   「新增能力不改 core」；放 oryxos-memory 混淆记忆/知识语义；放 oryxos-tool 违反
   原则中该模块「内置 Tool + MCP Client 三合一」的封闭定义。**实现 PR 须同步更新
   CLAUDE.md 模块表与 `docs/TechnicalSolution.md` §10。**
2. **跨模块契约上移 `oryxos-core/knowledge/`**：接口 + 值对象在 core，
   `oryxos-knowledge` 依赖倒置实现（同 006-memory 先例），禁止循环依赖。
3. **新表与新配置键**：`knowledge_documents` / `knowledge_chunks`（schema.sql 手工
   DDL，chunk 记录 dim + embedding_model 支撑 FR-014）；配置键 `knowledge.store`
   （默认 `sqlite`，本地后端向量索引存储的可插拔位，为 pgvector 预留——与 D1 统一）
   与 `knowledge.embedding.provider` / `knowledge.embedding.model`。后端插件选择
   **不设全局配置键**：由各库清单 `backend:` 字段声明（默认 `local`，FR-015）。
   embedding 未配置时**不静默回退**（DeepSeek 等无 embedding 端点，取「注册表首个
   可用项」会静默拿到错误 provider）：向量化按不可用处理——导入/重建给出可读配置
   指引报错，检索走关键词降级路（FR-013）；mock 需显式配置且恒可用。

### Phase 1 设计后复核

- 契约里 Reader/Chunker/EmbeddingModel/VectorStore 全部**不进** core 契约面——它们是
  `LocalKnowledgeBackend` 的私有分层（D9 采纳 AgentScope 核心极小化）。
- 检索基建（embedding 工厂、`RetrievalPipeline`、`VectorIndexStore`）接口形状不带
  knowledge 语义，先建在 `oryxos-knowledge` 内，供路线图方向 B 记忆语义化复用；
  不过早上移 core（FR-016 + D10 分层统一）。
- `KnowledgeHit` 出处四字段（库名/文件相对路径/片段位置/可跟读标记）为一等公民，
  `payload` 仅承载平台特有附加字段，契约测试断言出处非空或显式「出处不可用」。
- 看板只消费 `tool_invocations` 聚合（FR-023），不建第二套统计表；「出处引用率」由
  看板查询时关联会话最终回答文本近似计算，不新增埋点。
- 能力门禁在三处一致收口：REST 入口（可读 400/409）、管理台渲染（不出按钮）、
  契约测试（远程桩逐项核验 SC-011 三同）。

结论：全部宪法门禁通过；停点三项已声明，无需复杂度豁免。

## Project Structure

### Documentation (this feature)

```text
specs/014-knowledge-base/
├── spec.md
├── plan.md                  # 本文件
├── research.md              # D1~D11
├── traceability.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── knowledge-spi.md     # Java 契约 + 行为契约 + 工具 schema
│   └── rest-api.md          # REST 端点契约
├── checklists/
│   └── requirements.md
└── tasks.md                 # 下一阶段由 speckit-tasks 生成
```

### Source Code (repository root)

```text
oryxos-core/src/main/java/io/oryxos/core/knowledge/
├── KnowledgeRetriever.java          # 必选契约：retrieve(query)
├── KnowledgeAdmin.java              # 可选管理契约：建/删/导入/重建/状态
├── KnowledgeCapabilities.java       # 能力声明（检索必备，管理逐项可选）
├── KnowledgeBackend.java            # 后端插件 = Retriever + 能力声明 + 可选 Admin
├── KnowledgeBackendRegistry.java    # 按名显式注册表
├── KnowledgeService.java            # 门面：绑定范围圈定 → 后端路由 → 聚合融合
├── KnowledgeBindingService.java     # 软连接绑定 CRUD + 巡检（照抄 Skill 范式）
├── KnowledgeManifest.java           # KNOWLEDGE.md 清单读取
└── model/                           # KnowledgeQuery / KnowledgeHit / Citation /
                                     #   KnowledgeBaseInfo / DocumentStatus 值对象
oryxos-core/.../context/ContextLoader.java        # 加 appendKnowledge()

oryxos-knowledge/src/main/java/io/oryxos/knowledge/
├── LocalKnowledgeBackend.java       # 第一个插件：全能力本地实现
├── index/                           # KnowledgeIndexService（两段式+双缓冲+对账）、
│                                    #   Chunker、DocumentParser SPI（md/txt/pdf）
├── retrieve/                        # RetrievalPipeline：双路召回 + RRF 融合 + 精排槽位
├── watch/KnowledgeWatcher.java      # 热加载（照 WorkspaceWatcher 骨架）
└── builtin/KnowledgeTools.java      # @Tool retrieve_knowledge

oryxos-provider/.../ProviderEmbeddingModelFactory.java   # 复用 OpenAiApi 构建链
oryxos-provider/.../MockEmbeddingModel.java              # 哈希播种确定性单位向量

oryxos-storage/.../entity/{KnowledgeDocumentEntity,KnowledgeChunkEntity}.java
oryxos-storage/.../repository/{KnowledgeDocumentRepository,KnowledgeChunkRepository}.java
oryxos-storage/src/main/resources/schema.sql             # 追加两表 DDL

oryxos-web/.../controller/KnowledgeApiController.java    # CRUD/上传/重建/状态/看板
oryxos-web/.../controller/AgentApiController.java        # 绑定三件套（照 skills 三件套）
oryxos-web/.../controller/dto/                           # record DTO
oryxos-web/src/main/frontend/src/App.vue                 # knowledge 页（占位已存在）

oryxos-cli/.../OryxOsRuntime.java    # 装配：registry/backend/tools/watcher/工厂
oryxos-cli/.../InitCommand.java      # DIRS 加 "knowledge"
oryxos-cli/.../KnowledgeCommand.java # oryxos knowledge list

各模块 src/test/java 对应包 + 契约测试（参数化：LocalKnowledgeBackend ∥ 远程桩）
```

**Structure Decision**: 契约与绑定归 core（被 ContextLoader 消费，依赖倒置）；本地
后端、索引/检索流水线、检索工具归新建 `oryxos-knowledge`；JPA 实体与 DDL 归
storage（006 先例）；embedding 工厂归 provider；HTTP/视图归 web；装配唯一落点
`OryxOsRuntime`（同步改 cli/boot/根 pom）。

## Runtime Design

### 检索两层精度架构（D10）

1. **外层（ReAct 外环，免费自带）**：模型改写 query → 多次调 `retrieve_knowledge` →
   按出处 `read_file` 跟读原文 → 自行判断相关性（≈ LLM 精排器）。实现上只需两件事：
   注入指引文案提示「片段是入口、可按出处读原文」；本地库目录自动入读取白名单。
2. **内层（工具内流水线）**：`RetrievalPipeline` 三段——双路召回（向量余弦 ∥ 关键词
   LIKE，各取候选）→ RRF 名次融合（规避跨路分数不可比）→ 精排槽位（v1 空实现；
   声明 `rerank` 能力的远程后端结果直通）。关键词路同时是 embedding 不可用时的独立
   降级路径，降级结果带标记（FR-013）。
3. **多库聚合（FR-020 / Clarify-Q2）**：`KnowledgeService` 对绑定库逐库检索后跨库
   融合取全局 top-K；工具可选参数 `knowledge_base` 限定单库，未绑定/不存在返回可读
   错误。

### 索引流水线（D6 + Clarify-Q1/Q3）

1. **两段式上传**：REST 上传同步完成落盘 + 解析校验（格式不支持/扫描件 PDF 文本层
   为空当场 4xx 拒绝）；切分与向量化由虚拟线程后台推进，文档状态机
   `待索引 → 索引中 → 就绪 / 失败`（失败含可读原因）随时可查。
2. **变更检测**：SHA-256 内容指纹比对，未变文档不重复向量化。
3. **双缓冲重建**：新索引以 generation 标记后台构建，旧 generation 持续服务；构建
   完成在同步临界区原子切换并清理旧代；失败保留旧代与失败原因（FR-024）。
4. **热加载与对账**：`KnowledgeWatcher` 照 WorkspaceWatcher「非递归补挂」骨架盯
   `.oryxos/knowledge/`；启动 `reconcile()` 对账目录与索引差异（FR-010，SC-006
   30 秒窗口）；非法目录（缺清单/名称不一致）不注册、告警、不影响其余库。
5. **维度一致性**：chunk 落库记录 dim + embedding_model；与当前配置不一致时检索
   拒绝混合比较并提示重建（FR-014）。

### 绑定与安全（D4）

1. `KnowledgeBindingService` 照抄 `AgentSkillBindingService`：
   bind/unbind/replace/inspect/references/reconcile + dangling/escaped/
   invalid-target/name-mismatch 检测；与 Skill 绑定共用工作区同步临界区。
2. 删除被引用库默认拒绝并返回引用 Agent 清单（`KnowledgeReferencedException` →
   409，照 `SkillReferencedException` 先例）。
3. 绑定仅管理面动作：不注册任何可改绑定的工具（FR-019）；运行时 `KnowledgeTools`
   经 `ToolExecutionContext` 取当前 agentName 圈定检索范围。

### 插件契约与能力门禁（D9）

1. 契约拆分规避「契约谎言」：`KnowledgeRetriever` 必选、`KnowledgeAdmin` 可选、
   `KnowledgeCapabilities` 声明能力集；对未声明能力的调用在 REST 入口返回可读拒绝，
   不以运行时异常暴露（FR-006）。
2. `KNOWLEDGE.md` 清单声明 `backend:`（缺省 local）；远程库目录只存清单与连接引用，
   凭证 `${ENV_VAR}` 占位（FR-015）。
3. v1 附带「仅检索能力」测试桩后端，参数化契约测试逐项核验 SC-011 三同（同工具/
   同出处契约/同审计路径）。

### 观测与看板（D11）

1. 埋点一次到位（FR-022）：`retrieve_knowledge` 的工具结果为结构化 JSON——命中明细
   （库/文档/片段/分数）+ 查询原文 + 耗时 + 零结果/降级标记——随 `tool_invocations`
   落库，天然被审计覆盖，后续评测集/优化建议直接消费。
2. 看板（FR-023）：`KnowledgeApiController` 聚合查询 `tool_invocations`（检索次数、
   零结果率、降级率、命中文档分布、时间过滤、零结果查询列表）；出处引用率关联会话
   最终回答文本近似计算。不建第二统计路径，指标与审计 100% 可核对（SC-009）。

## Delivery Order（对应 PR 切分）

1. **契约骨架**：core 契约接口 + 值对象 + 注册表 + 绑定服务（失败测试先行）、
   schema.sql 两表、storage 实体与 Repository；同步 CLAUDE.md 模块表与
   TechnicalSolution §10。
2. **本地后端**：解析层（md/txt/PDFBox）→ 切分 → embedding 工厂（provider）+ mock
   确定性向量 → SQLite 向量存取 → 双路召回 + RRF 流水线；D8 契约测试钉行为。
3. **运行时**：`KnowledgeTools` 注册 + `ContextLoader.appendKnowledge()` +
   read_file 白名单联动 + 审计埋点结构；US1 全场景走通（mock）。
4. **管理面**：REST 全量端点 + 绑定三件套 + 管理台 knowledge 页与 Agent 绑定视图 +
   一句话生成的知识库候选注入（FR-018）；US2/US3 走通。
5. **GitOps**：Watcher 热加载 + 启动对账 + 两段式/双缓冲收尾 + `oryxos knowledge
   list` + `init` 目录；US4 走通。
6. **观测**：看板端点与页面 + 远程桩契约核验；US5/US6/US7 收口，跑 quickstart 全量
   验收。

## Complexity Tracking

无宪法违例。新建 `oryxos-knowledge` 模块已按宪法在停点声明（理由 D3）；检索基建
接口先建在该模块内而非 core，是为避免在记忆侧复用需求到来前制造空转抽象——上移
时机由路线图方向 B 触发。
