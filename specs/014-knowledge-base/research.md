# Research: 知识库（Knowledge Base）

**Created**: 2026-08-17　**Feature**: [spec.md](spec.md)　**Phase**: 前置调研（供 /speckit-plan 消费）

本文备案 spec 之外的技术调研结论。格式对照 006-memory-pluggable 的决策编号（D1~Dn），每条含决策、理由、被否方案。

---

## D1. 向量检索实现：纯 Java 余弦暴力检索 + SQLite BLOB 存向量

**决策**：chunk 向量以 BLOB 存 SQLite `knowledge_chunks` 表；检索时全量加载所属知识库的向量做余弦相似度暴力扫描（纯 Java，可选 `float[]` 点积循环），作为流水线（D10）的语义召回路。以 `KnowledgeStore` 可插拔接口（配置键 `knowledge.store`，默认 `sqlite`）隔离，v0.4 换 pgvector 时仅替换存储实现。注意与库级后端插件选择区分：后端插件（local / 远程）由各库清单 `backend:` 字段声明（默认 `local`），无全局配置键；`knowledge.store` 只选择本地后端的向量索引存储。

**理由**：
- 目标规模（单库万级 chunk、1024 维）下暴力扫描为几十毫秒量级，满足 SC-002（1 秒内），KISS/YAGNI。
- 零新依赖、零新服务，与「单二进制」交付形态一致。

**被否方案**：
- **sqlite-vec 扩展**：经 xerial sqlite-jdbc 加载原生扩展摩擦大（官方 issue #1212 仍开放、需按平台分发 .so/.dll、`enable_load_extension` 安全面扩大），否。
- **Apache Lucene HNSW**：纯 Java 且 Apache 系，但引入重依赖 + 磁盘索引文件成为 SQLite 之外的第二状态存储，违背「状态外置单一事实源」，当前规模无收益，否（可作为未来后端档位备选）。
- **SQLite FTS5 作主检索**：unicode61 对中文逐字分词效果差，第三方中文分词器（wangfenjin/simple）又是原生扩展，否；关键词兜底档用现有 `recallByKeyword` 同款 `LIKE` 即可。

## D2. Embedding：复用 Provider 注册表 + Spring AI OpenAiEmbeddingModel

**决策**：`oryxos-provider` 新增 `ProviderEmbeddingModelFactory.buildOne(name, apiKey, baseUrl, model)`，复用与 ChatModel 同一套 `OpenAiApi` 构建链（含 `stripTrailingV1`、HTTP/1.1、超时工厂）；配置 `knowledge.embedding.provider` / `knowledge.embedding.model` 按名引用 Provider 注册表，不另起凭证（FR-007）。mock provider 返回确定性向量（文本哈希播种的伪随机单位向量），保证 CI 可断言（SC-004）。

**理由**：
- `spring-ai-openai:1.1.8` jar 内已含 `OpenAiEmbeddingModel` / `EmbeddingRequest`，**零新增 Maven 依赖**（仓库实测）。
- DashScope 兼容模式有标准 `POST /compatible-mode/v1/embeddings`（text-embedding-v4，支持 dimensions 参数，最大 8192 token、批 10 条），与现有 base-url 约定（不含 /v1，内部补路径）完全同构；DeepSeek 无 embedding 端点——文档需说明 embedding provider 可与对话 provider 不同。

**被否方案**：DashScope 原生 SDK（多一套协议）；本地 embedding 模型（ONNX 等，重依赖）——均否。

## D3. 模块落位：新建 oryxos-knowledge，契约上移 core

**决策**（plan 阶段按宪法声明并停点确认）：

| 内容 | 落位 |
|---|---|
| 标准操作契约（见 D9）：`KnowledgeRetriever`（必选）、`KnowledgeAdmin`（可选管理面）、`KnowledgeCapabilities`（能力声明）、`KnowledgeQuery` / `KnowledgeHit` / `KnowledgeBaseInfo` 值对象、`KnowledgeBackendRegistry`（按名注册表）、绑定读取接口 | `oryxos-core/io/oryxos/core/knowledge/` |
| 内置本地后端（第一个插件）：`LocalKnowledgeBackend`（实现 Retriever + Admin 全能力）+ 切分/embedding/索引流水线 + `builtin/KnowledgeTools`（`@Tool retrieve_knowledge`） | **新建 `oryxos-knowledge`**（依赖 core + storage，pom 照抄 oryxos-memory：需 `spring-ai-model` 取 `@Tool`） |
| `KnowledgeDocument` / `KnowledgeChunk` JPA 实体 + Repository + `schema.sql` DDL | `oryxos-storage`（同 006 D4 分工先例） |
| `ProviderEmbeddingModelFactory` | `oryxos-provider` |
| `KnowledgeApiController` + DTO（record，`controller/dto/`） | `oryxos-web` |
| bean 装配（`@Value("${knowledge.store:sqlite}")` + switch） | `oryxos-cli/OryxOsRuntime`（唯一装配层；同步改 cli/boot/根 pom） |

**理由**：`ContextLoader`/`PromptBuilder` 在 core 要消费门面 → 契约必须上移（否则成环，同 006 D1）；塞 oryxos-memory 混淆「记忆/知识」语义；塞 oryxos-tool 违反宪法原则八「三合一」定义。**须同步更新 CLAUDE.md 模块表与 docs/TechnicalSolution.md §10。**

## D4. Agent 绑定：软连接同构复用 Skill 范式

**决策**：`.oryxos/agents/<agent>/knowledge/<kb>` → `../../../knowledge/<kb>` 相对软连接为唯一绑定真相源；实现照抄 `AgentSkillBindingService`（core，`bind/unbind/replaceBindings/inspect/references/reconcile` + dangling/escaped/invalid-target/name-mismatch 检测）。删除被引用知识库默认拒绝（FR-011，同 `SkillReferencedException` → 409 先例）。

**注意**：路线图旧文字「knowledge: [名] 按名引用」写于宪法 v2.0.0 之前——本决策取软连接路线以与原则 IV 一致，plan 阶段作为停点确认项（若维护者坚持 frontmatter 路线，改动集是 `Profile` record 加字段 + `ProfileLoader.toProfile()` + web DTO 三处）。

## D5. 渐进披露注入点与工具链路

- 元数据注入：`ContextLoader` 加 `appendKnowledge()`（对照 `appendSkills()`，81~110 行模式）：每轮注入绑定库的 `name + description + 「用 retrieve_knowledge 检索」`；零绑定零注入（FR-005）。可参照 `appendOutputDir()` 按 `profile.tools()` 是否含 `retrieve_knowledge` 决定注入。
- 工具注册：`OryxOsRuntime.toolRegistry(...)` 加 `registry.registerAnnotated(new KnowledgeTools(knowledgeService))`；同步 `OryxToolContractTest` 参数化注册面。
- 检索范围：`KnowledgeTools` 经 `ToolExecutionContext`（MemoryTools 同款）拿当前 agentName → 圈定其绑定库。
- 审计：走 `ToolExecutor` 自动落 `tool_invocations`，零额外工作（FR-012）。

## D6. 存储 DDL 与热加载

- `schema.sql` 末尾追加 `knowledge_documents` / `knowledge_chunks`（`CREATE TABLE IF NOT EXISTS` + 中文块注释 + `idx_` 索引；宪法 VIII：schema.sql 为唯一权威，ddl-auto=none）。chunk 表记录 `dim` 与 `embedding_model`（FR-014 维度一致性校验）。
- 文档变更检测：内容指纹（SHA-256）比对，避免重复向量化。
- 热加载：照抄 `WorkspaceWatcher` 的「非递归补挂」骨架（根目录盯子目录增删、子目录盯文件变更），驱动 `KnowledgeIndexService`；启动时 `reconcile()` 对账（FR-010、SC-006 的 30 秒窗口）。
- 工作区：`InitCommand.DIRS` 加 `"knowledge"`。

## D7. Web / 管理台

- 端点：`/api/v1/knowledge` CRUD + `POST /{name}/documents`（上传）+ `POST /{name}/reindex` + 索引状态；`AgentApiController` 加 `/{name}/knowledge` 绑定三件套（GET/PUT/DELETE，照 skills 三件套 298~330 行）。统一 `ApiResponse`，异常走 `GlobalExceptionHandler`（新增 `KnowledgeReferencedException` → 409）。
- 前端：`App.vue` 占位已在（`TOP_NAV` key `knowledge`、1530 行占位表）——补 `path`、列定义与详情操作即可；样式循 `oryxos-admin-ui` skill 规范。
- 站点文档 `website/zh/docs/architecture.md:80`「知识库（占位）」落地后同步。

## D8. 行为契约（契约测试钉死，参数化覆盖各后端）

1. 检索结果必须带完整出处（库名 + 文件相对路径 + chunk 序号）——SC-003。
2. 切分不跨文档；chunk 可回溯原文位置。
3. 检索范围限于发起 Agent 的绑定库；零绑定返回可读错误。
4. embedding 不可用 → 检索降级关键词并标注；导入显式失败可重试（FR-013）。
5. 维度/模型不一致 → 拒绝混合比较并提示重建（FR-014）。
6. mock 向量确定性：同文本恒同向量，排序可重复（SC-004）。

## D9. 对标 AgentScope-Java RAG 抽象（v2.0.x，Apache-2.0，本机 `~/work/agentscope-java`）：采纳其分层哲学，规避其四个已验证的坑

AgentScope 的 RAG 结构 = 核心 2 方法契约（`Knowledge.addDocuments/retrieve`）+ 3 值对象（`Document/DocumentMetadata/RetrieveConfig`）+ 5 个插件（simple 本地实现 / bailian·dify·ragflow·haystack 远程代理）。**其整套 RAG API 在 2.0.0 已被官方 `@Deprecated(forRemoval)` 等待 v2 重写**——既是最好的参考，也是最好的反面教材。

**采纳**：

1. **核心契约极小化**：Reader/Chunker/EmbeddingModel/VectorStore 全部不进核心契约，只属于本地后端插件的内部分层。OryxOS 同样：`oryxos-core/knowledge/` 只放标准操作接口 + 值对象，切分/向量化流水线是 `LocalKnowledgeBackend` 的私有实现细节。
2. **「本地实现与远程代理同一契约」**：simple 与 ragflow/dify 在使用侧完全可替换——这正是「知识库以插件挂载」的形态基础。检索参数最小公约数取 `topK + scoreThreshold`（AgentScope 实测远程映射也只有这两个），平台特有参数沉到各插件配置。
3. **`payload` 逃生舱**：`KnowledgeHit` 保留 `Map<String,Object> payload` 承载平台特有字段（highlight、rerank 分数等），契约字段保持稳定。
4. **检索工具 schema**：`retrieve_knowledge(query: string 必填, limit: int 选填)` 两字段极简 schema 已被验证够用，照抄。

**规避（四个坑 → OryxOS 的对策）**：

1. **契约谎言**：AgentScope 的远程插件对 `addDocuments` 一律运行时 `UnsupportedOperationException`（文档自认是为可替换性妥协）。→ OryxOS **拆分契约**：`KnowledgeRetriever`（必选，仅 `retrieve`）+ `KnowledgeAdmin`（可选管理面：建库/删库/导入/重建/状态）+ `KnowledgeCapabilities` 能力声明；REST 层与管理台按能力探测降级渲染（只读库不出上传按钮），入口处返回可读错误而非运行时炸（spec FR-006/FR-009）。
2. **出处不是一等公民**：AgentScope 把文件名/来源埋进弱类型 payload，且内置的 Hook/Tool 格式化**根本不读 payload**——默认给模型的文本没有任何出处，citation 要自己绕开内置消费器。→ OryxOS 把出处（库名 + 文件相对路径 + chunk 位置）定为 `KnowledgeHit` 一等字段与硬契约（spec FR-004/SC-003），远程后端映射不到时显式标注「出处不可用」（FR-015）。
3. **无插件发现机制**：AgentScope 靠 `builder().knowledge(k)` 显式传入，无 SPI、无配置装配——对库合理，对**运行时底座**不可行（Agent 是目录声明的，没有人写 Java 代码）。→ OryxOS 用 `KnowledgeBackendRegistry` 按名显式注册（宪法 III「显式映射」同款哲学），知识库清单 `KNOWLEDGE.md` 声明 `backend:`（缺省 local），凭证走环境变量（同 mcp_servers.yaml 模式）。
4. **响应式契约**：全 `Mono` 返回 + 工具层 `.block()` 拍平。→ OryxOS 宪法 VII：契约直接同步签名，虚拟线程扛并发，不引入 Reactor。

**另两处 AgentScope 的文档/实现漂移引为镜鉴**（javadoc 说注入 system 消息、实际注入 user 消息；文档写 `STATIC` 枚举、代码是 `GENERIC`）：契约行为以参数化契约测试钉死（D8），不依赖文档描述。

**消费模式取舍**：AgentScope 提供 GENERIC（每轮自动检索注入）/ AGENTIC（模型自主调工具）双模式。OryxOS v1 只做 AGENTIC——与 Skill 渐进披露同一哲学（元数据常驻、正文按需），且规避 GENERIC 的每轮 embedding 开销与上下文污染；GENERIC 式自动注入留待真实场景需求出现再评估（已记入 spec Assumptions）。

## D10. 检索流水线：双路召回 + RRF 融合，精排留槽位；两层精度架构（2026-08-18 维护者拍板）

**背景**：业界标准四段式（多路召回 → RRF 融合 → cross-encoder 精排 → 生成）实测能把 recall@10 从 78% 提到 91%（纯向量对约 40% 真实查询失手——专名/代号/精确短语靠关键词路补）；QAnything 为「精度靠精排」路线代表。但 2025-05 起 Anthropic 在 Claude Code 撤掉向量 RAG 换 agentic search（grep + 多轮迭代），Cursor/Windsurf/Cline/Devin 跟进；AAAI 2026 论文测得 agentic 关键词检索达 RAG 忠实度 94.5% 且零向量库。适用边界：语料可枚举、harness 支持迭代 → agentic 赢；语料大、信号偏语义、一次性问答 → staged 流水线赢。

**决策**（对应 spec FR-004/FR-016/FR-017 与 Assumptions）：OryxOS 是 harness，精度架构分两层——

```
外层（ReAct 外环，免费自带）：模型改写 query → 多次调 retrieve_knowledge
                             → 按出处 read_file 跟读全文 → 自行判断相关性（≈ LLM 精排器）
内层（工具内流水线）：双路召回（向量余弦 ∥ 关键词 LIKE）→ RRF 融合（按名次，规避跨路分数不可比）
                     → 精排槽位（v1 空实现，能力声明位保留）
```

1. **双路召回是并行双路，不是「向量为主关键词兜底」**（修订 D1 时代的表述）：两路各取候选、RRF 融合；关键词路同时承担 embedding 不可用时的独立降级路径（FR-013）。
2. **精排 v1 不做**：cross-encoder 要新模型部署、LLM-as-reranker 每检索多一次 LLM 调用；万级 chunk + 外环迭代下边际收益不明。契约留 `rerank` 能力位：远程后端（RAGFlow/Dify 自带 rerank）声明该能力后结果直通；内置精排待评测基线（v1.0 评测 harness）建立后凭数据决定。
3. **全文跟读为硬需求**（FR-017）：本地知识库目录自动纳入 `read_file` 白名单（先例：`oryxos.root` 改根后自动纳入文件白名单的同款机制）；这使外环具备「chunk 入口 → 原文补全」能力，是 agentic 路线的关键一环。远程命中无本地文件时明确标注不可跟读。
4. **分层统一（记忆 vs 知识）**：语义层分离——`recall_memory`/`retrieve_knowledge` 两个工具、两套门面（写入者/信任级/作用域/注入方式四维差异，Letta 式统一入口被否）；基建层统一——embedding 工厂、索引存储、流水线槽位设计为底座通用组件（plan 阶段定包位，倾向 `oryxos-knowledge` 内先建、接口形状不带 knowledge 语义，供路线图方向 B 记忆语义化复用，避免过早上移 core 增加空转抽象）。

**参考对象定位**（AgentScope 仅供契约形状参考，检索质量设计另找老师）：RAGFlow（流水线分段与出处/高亮字段设计）、QAnything（两阶段论——精排槽位存在的理由）、Dify（rerank 作为独立可插拔配件的配置形态）、Claude Code/OpenClaw/Hermes（外环哲学，默认实现路线）、Letta（记忆分层框架，用于划定记忆/知识边界）。

## D11. 效果评估体系与文件类型（2026-08-18 brainstorming 收敛，维护者拍板）

**评估体系三档与取舍**：A 运营看板（消费审计数据聚合指标）/ B 评测集回归（标准问题→期望命中，一键跑报告）/ C 自动优化建议（零结果分析→补文档建议）。**拍板 v1 做 A**：`retrieve_knowledge` 已强制落 `tool_invocations`，看板是审计数据的第一个消费者，正接路线图「审计→数据飞轮」；B 与 v1.0 评测 harness 重叠，做早返工；C 依赖 A 的数据积累。**关键工程约束**：埋点结构（FR-022 命中明细 + 分数 + 零结果/降级标记 + 查询原文）按 B/C 需要一次设计到位，避免二次埋点。「出处引用率」定义为命中出处出现在最终回答文本中的比例（近似度量，需求层足够）。

**文件类型拍板 B 档**：v1 = md/txt + 文本型 PDF（Apache PDFBox，单一新依赖；出处用页码）；扫描件导入时识别拒绝（文本层为空即拒）。否 C 档 Tika 全家桶（AgentScope simple 的 pdfbox+poi+tika 路线）——依赖重、长尾解析质量差。解析器为本地后端内部可扩展分层（Reader 式），加格式不动契约（FR-003）。

## 外部参考

- sqlite-vec × xerial 集成障碍：github.com/xerial/sqlite-jdbc/issues/1212
- FTS5 中文限制与 simple 分词器：github.com/wangfenjin/simple
- Lucene KnnFloatVectorField（备选后端）：lucene.apache.org/core/9_12_1/core/.../KnnFloatVectorField.html
- DashScope 兼容模式 embeddings（text-embedding-v4）：alibabacloud.com/help/en/model-studio/embedding
- AgentScope-Java RAG（本机克隆 `~/work/agentscope-java`，v2.0.x，Apache-2.0）：核心契约 `agentscope-core/src/main/java/io/agentscope/core/rag/`，插件 `agentscope-extensions/agentscope-extensions-rag/`（simple/bailian/dify/ragflow/haystack）
- 混合检索 + RRF + 精排流水线实测数据：digitalapplied.com/blog/hybrid-search-bm25-vector-reranking-reference-2026；infoq.com/articles/vector-search-hybrid-retrieval-rag
- 两阶段检索与 rerank 论证（QAnything/BCEmbedding）：github.com/netease-youdao/BCEmbedding；pinecone.io/learn/series/rag/rerankers
- Agentic search 路线证据：milvus.io/blog/why-im-against-claude-codes-grep-only-retrieval...（含 Anthropic 弃向量时间线）；arxiv.org/pdf/2602.23368（Keyword search is all you need）；arxiv.org/pdf/2605.15184（Is Grep All You Need?）
- 记忆分层与统一检索：letta.com/blog/agent-memory；vectorize.io/articles/mem0-vs-letta
