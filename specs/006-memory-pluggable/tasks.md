# Tasks: Memory——让 Agent 记得住事的可插拔记忆层

**Input**: specs/006-memory-pluggable/（plan.md 含 D1~D6 / data-model.md / contracts/memory.md / quickstart.md）

**Tests**: 课件 harness——`MemoryStoreContractTest` 参数化对三档统一跑；两个中文名关键回归原样落地（英文方法名 + @DisplayName）。

**Organization**: 契约上移地基（D1/D2）→ 存储件 → 三档 store（契约先行）→ 门面+工具 → 装配 → Polish。

## Phase 1: Setup

- [x] T001 基线：`mvn test -q` 确认 16~20 节全绿（139 测试）
- [x] T002 依赖：`oryxos-memory/pom.xml` +spring-web、+spring-boot-starter-test(test)；`oryxos-cli/pom.xml` +oryxos-memory；`mvn dependency:tree -pl oryxos-memory | grep -E "spring-web|oryxos-storage"` 确认

## Phase 2: Foundational（D1 契约上移 + D2 PromptBuilder 改造——⚠️ 停点确认项）

- [x] T003 [P] D1 契约上移：新建 `oryxos-core/src/main/java/io/oryxos/core/memory/`——`MemoryService.java`（buildContext(Session)/remember(content,scope)/recall(keyword)）+ `MemoryScope.java`（enum CORE/ARCHIVAL）
- [x] T004 D2 PromptBuilder 改造（扩展非破坏）：`oryxos-core/.../agent/PromptBuilder.java` 加构造 `(ContextLoader, Map, MemoryService, Clock)`；旧构造保留传 null；build() 的"②长期记忆恒空"留位改为 `if (memoryService != null)` 拼 `memoryService.buildContext(session)`
- [x] T005 D2 回归门禁：`mvn test -pl oryxos-core -am -Dtest=PromptBuilderTest` 全绿（17 节断言零改，旧构造路径记忆段仍空）

## Phase 3: 存储件（SQLite 档，US1/US3 支撑）

- [x] T006 [P] 存储三件：`oryxos-storage/src/main/resources/schema.sql` 追加 memory_entries DDL（id/scope NOT NULL 索引/content/created_at + idx_memory_scope）+ `MemoryEntry.java`（@Table("memory_entries") @PrePersist createdAt）+ `MemoryEntryRepository.java`（findByScopeOrderByIdAsc 核心全量 / 归档 findByScope + Pageable LIMIT desc / searchArchival LIKE）
- [x] T007 [US3] MemoryEntryRepositoryTest：`oryxos-storage/.../MemoryEntryRepositoryTest.java`（@DataJpaTest + @TempDir SQLite 文件库，16 节模式）——手工建表能存读、按 scope 查、归档 LIMIT、LIKE 检索

## Phase 4: 三档 store + 契约测试（US2/US3，harness 先行）

- [x] T008 [US2] MemoryStoreContractTest：`oryxos-memory/.../MemoryStoreContractTest.java`——@MethodSource allStores 提供三实例（MarkdownMemoryStore@TempDir / SqliteMemoryStore / InMemoryMemoryStore 代 mem0）；**关键回归** `truncationKeepsCoreIntact` `@DisplayName("截断只裁归档区_核心记忆一字不能少")`（灌核心1条+归档500条，load 后核心完整、归档流水0被裁、499保留）+ `writeIsImmediatelyReadable_noCache` `@DisplayName("写入后立刻可读_不允许有缓存")`（append 后 load/recallByKeyword 立即命中）+ scope 路由 + recall 只搜归档（断言逐条照课件保真）
- [x] T009 [US2] InMemoryMemoryStore：`oryxos-memory/.../InMemoryMemoryStore.java`（内存 Map 实现四契约；契约集里代 mem0、兼作测试基建）
- [x] T010 [US3] MarkdownMemoryStore：`oryxos-memory/.../MarkdownMemoryStore.java`（构造注入 .oryxos 根 Path；`## 核心记忆`/`## 归档记忆` 分区；load 每次 Files.readString 不缓存；truncateIfNeeded 只接归档段字符串裁尾 4000；recallByKeyword contains 只搜归档）
- [x] T011 [P] [US3] MarkdownMemoryStoreTest：`oryxos-memory/.../MarkdownMemoryStoreTest.java`（字符串截断边界、区块 header 解析、恰好等于上限不截断）
- [x] T012 [US3] SqliteMemoryStore：`oryxos-memory/.../SqliteMemoryStore.java`（注入 MemoryEntryRepository；append=insert；load=核心全量+归档 LIMIT 100 拼接；recallByKeyword=searchArchival LIKE）
- [x] T013 [US3] Mem0MemoryStore：`oryxos-memory/.../Mem0MemoryStore.java`（RestClient 构造注入，baseUrl/userId 经装配传入；append→POST add 请求体带 scope metadata；load→按 scope 取；recallByKeyword→search；端点按 Mem0 OSS 约定注释注明"以部署版本为准"）
- [x] T014 [US3] Mem0MemoryStoreTest：`oryxos-memory/.../Mem0MemoryStoreTest.java`（mock RestClient 或 JDK HttpServer 假服务：append 发出的请求体带 content+scope、recall 转发查询、非成功响应异常上抛不吞——不碰真 server）
- [x] T015 [US2/US3] 阶段门禁：`mvn test -pl oryxos-memory -am -Dtest='MemoryStoreContractTest,MarkdownMemoryStoreTest,Mem0MemoryStoreTest'` 绿

## Phase 5: 门面 + 工具（US1/US4）

- [x] T016 [US1] DefaultMemoryServiceTest：`oryxos-memory/.../DefaultMemoryServiceTest.java`——buildContext 返回核心记忆+会话历史组合、归档区不整体注入（`@DisplayName("buildContext返回核心记忆加会话历史")`）
- [x] T017 [US1] DefaultMemoryService：`oryxos-memory/.../DefaultMemoryService.java`（implements core MemoryService；组合 LongTermMemoryStore + SessionManager；buildContext=store.load()+会话历史；remember/recall 转发）
- [x] T018 [US4] MemoryToolsTest：`oryxos-memory/.../builtin/MemoryToolsTest.java`——save_memory 缺省写归档、非法 scope 报错点名；recall_memory 未命中返回"没有找到相关记忆"不抛异常（`@DisplayName` 保守点）
- [x] T019 [US4] MemoryTools：`oryxos-memory/.../builtin/MemoryTools.java`（@Tool save_memory(content,scope 缺省 archival)/recall_memory(keyword)；转发 MemoryService；20 节注解管道注册）
- [x] T020 [US4] 阶段门禁：`mvn test -pl oryxos-memory -am` 全绿

## Phase 6: 装配 + Polish

- [x] T021 装配：`oryxos-cli/.../OryxOsRuntime.java`——+MemoryService bean（读 memory.backend 造 store：markdown 默认/sqlite 注 MemoryEntryRepository/mem0 注 RestClient+${MEM0_BASE_URL}；DefaultMemoryService 组合 SessionManager）；PromptBuilder bean 改用 D2 新构造注入 memoryService；registry.registerAnnotated(new MemoryTools(memoryService))；`application.yml` +memory.backend: markdown
- [x] T022 全仓硬门禁：`mvn clean verify` 全绿（含静态检查），红了修实现
- [x] T023 H4 六条自查 + 交付物 ls/grep 核对（含：memory 读写 grep 无明文 key、core 不依赖 memory 实现包、save_memory/recall_memory 注册进 registry）
- [x] T024 验收报告：`specs/006-memory-pluggable/acceptance-report.md`（六项证据 + 人工项：三档切换、Mem0 真连、真模型跨会话、USER.md 只读）

## Dependencies

- T002→全部；T003→T004→T005（D1 先于 D2）；T003 也阻塞 memory 全部（实现 core 接口）。
- T006→T007/T012；T008 先于 T010/T012/T013（契约先行，允许伴随）；T009 支撑 T008 参数化。
- T016<T017、T018<T019；T017 依赖三档 store 之一 + SessionManager。
- T021 依赖全部主代码。
- 并行：T003∥T006；T011 独立；T009/T010 不同文件。

## Implementation Strategy

- MVP = Phase 2+3+4（契约上移 + 三档 store + 契约测试，"接口墙"证明就位）；Phase 5 补门面工具；T021 一次装配。
- 每阶段门禁当场修红。
