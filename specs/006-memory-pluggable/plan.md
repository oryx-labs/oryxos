# Implementation Plan: Memory——让 Agent 记得住事的可插拔记忆层

**Branch**: `class-22`（用户指定） | **Date**: 2026-07-12 | **Spec**: [spec.md](./spec.md)

## Summary

`MemoryService` 门面 + 可插拔 `LongTermMemoryStore` 后端接口，一次交付三档实现（Markdown / SQLite / Mem0），靠 `memory.backend` 配置选装配。关键依赖方向决策（D1）：`MemoryService`/`MemoryScope` 接口上移 oryxos-core（`PromptBuilder` 要注入它、而 memory→core 依赖已存在，接口留在 memory 会成环——与 16 节 D1 契约上移同款）；实现全在 oryxos-memory，SQLite 档的 JPA 实体/Repository/建表脚本落 oryxos-storage（与 17 节 ToolInvocation 同款分工）。四条行为契约由一套参数化契约测试对三档统一钉死。

## Technical Context

**Language/Version**: Java 21，同步阻塞（宪法 VII）

**Primary Dependencies**: Spring Data JPA + SQLite（SqliteMemoryStore，复用既有 storage 基建）；RestClient（Mem0MemoryStore，spring-web，19/20 节已 javap 实核）；Jackson（Mem0 JSON）。**无新第三方 Java 库**——Mem0 是外部 HTTP 服务不是库，走 REST（端点按 Mem0 OSS 约定，课件已声明"以部署版本为准"）

**Storage**: `memory_entries` 表（SQLite 档，手工 schema.sql 追加，与 sessions/审计表同口径）；Markdown 档写 `.oryxos/memory/MEMORY.md`；Mem0 档数据在外部自托管服务

**Testing**: `MemoryStoreContractTest` 参数化遍历三档（md 用 @TempDir、sqlite 用 @DataJpaTest+SQLite 文件库、mem0 用**内存假后端替身**）；各档专属测试；Mem0 真实 REST 用 mock RestClient（不碰真服务）

**Constraints**: 四契约（不缓存/核心不截断/scope 显式/关键词检索）；Mem0 地址凭证 `${ENV}` 占位、自托管；测试方法名英文 + @DisplayName 课件中文原名；避开 Java 18+ 语法形态；日志字符消毒

**Scale/Scope**: core 2 个上移接口/枚举 + PromptBuilder 构造扩展；memory ~7 类；storage 实体+Repo+DDL；cli 装配改造；~6 个测试类

## Constitution Check

| # | 原则 | 符合性 |
|---|---|---|
| I | 自实现 ReAct | ✅ 不碰循环；记忆经门面注入 Prompt |
| II | Spring AI 只做两件事 | ✅ MemoryTools 走 20 节 @Tool 注解管道（schema 生成）；无自动执行 |
| III | Provider 显式映射 | ✅ 不涉及 |
| IV | 模块归属 | ✅ Memory 主体在 oryxos-memory；跨模块契约（MemoryService）上移 core（宪法 v1.1.0 依赖倒置） |
| V | 审计 Day One | ✅ save_memory/recall 作为工具经 ToolExecutor 落 tool_invocations |
| VI | 沙箱/凭证 | ✅ Mem0 地址凭证 `${ENV}`；记忆读写本地档不涉外、Mem0 档是配置的外部服务 |
| VII | 同步 + 虚拟线程 | ✅ 文件/JPA/RestClient 同步；无异步原语 |
| VIII | 手工建表 | ✅ memory_entries 手工 schema.sql；ddl-auto=none |

**Post-design 复查**：D1 接口上移不改任何方法语义（与 16 节 D1 同性质）；D2 PromptBuilder 加构造重载为**扩展非破坏**（旧构造保留、17 节测试零改）——触软门禁 1（清单外接口上移）/4（碰前序 PromptBuilder），tasks 停点列明。

## Project Structure

```text
oryxos-core/                         # 【D1 跨模块契约上移，同 16 节 ProviderService】
└── src/main/java/io/oryxos/core/memory/
    ├── MemoryService.java           #   接口：buildContext(Session) / remember(content,scope) / recall(keyword)
    └── MemoryScope.java             #   enum CORE / ARCHIVAL
oryxos-core/.../agent/PromptBuilder.java   # 【D2 改造，扩展非破坏】+构造重载注入 MemoryService；
                                            #   build() 的"②长期记忆恒空"留位改为 memoryService.buildContext 注入

oryxos-memory/                       # 【交付物主体，22 节落位】
├── pom.xml                          #   +spring-web（RestClient）、+spring-boot-starter-test(test)
└── src/main/java/io/oryxos/memory/
    ├── LongTermMemoryStore.java     #   后端接口：append / load / recallByKeyword
    ├── DefaultMemoryService.java    #   MemoryService 实现：组合 store（长期）+ SessionManager（会话）
    ├── MarkdownMemoryStore.java     #   档一：MEMORY.md 分区，字符串截断(4000)，contains 检索
    ├── SqliteMemoryStore.java       #   档二：注入 MemoryEntryRepository，LIMIT 100 截断，LIKE 检索
    ├── Mem0MemoryStore.java         #   档三：RestClient 打自托管 mem0，add/get/search
    └── builtin/MemoryTools.java     #   save_memory / recall_memory（@Tool，20 节管道注册）
oryxos-memory/.../test/              #   MemoryStoreContractTest（参数化三档）+ 各档专属 + MemoryToolsTest + DefaultMemoryServiceTest
                                     #   InMemoryMemoryStore（契约集里代 mem0 的假替身 + 测试基建）

oryxos-storage/                      # 【SQLite 档持久化件，同 17 节 ToolInvocation 分工】
├── src/main/java/io/oryxos/storage/
│   ├── MemoryEntry.java             #   JPA 实体 @Table("memory_entries")
│   └── MemoryEntryRepository.java   #   findByScope / findRecentArchival(LIMIT) / searchArchival(LIKE)
└── src/main/resources/schema.sql    #   追加 memory_entries DDL

oryxos-cli/.../OryxOsRuntime.java    # 【装配】按 memory.backend 造 store → DefaultMemoryService bean
                                     #   → 注入 PromptBuilder（D2 新构造）+ registerAnnotated(MemoryTools)
oryxos-cli/.../application.yml       # memory.backend: markdown（默认）| sqlite | mem0
```

**关键设计（research 合并于此）**：

- **D1 契约上移**：`MemoryService`+`MemoryScope` 进 `io.oryxos.core.memory`（core 零依赖包）。理由同 16 节 D1：PromptBuilder（core）必须依赖它，memory→core 依赖已在，接口留 memory 会环。
- **D2 PromptBuilder 改造（扩展非破坏）**：加构造 `(ContextLoader, Map, MemoryService, Clock)`；旧构造保留（内部传 null MemoryService，记忆段空——17 节测试零改）。build() 的"②长期记忆"留位改为 `if (memoryService != null) 拼接 memoryService.buildContext(session)`。
- **D3 三档 store**：Markdown（4000 字符裁归档）、Sqlite（注入 storage 的 Repository，LIMIT 100）、Mem0（RestClient，scope 落 metadata）。四契约各自落地方式不同、行为一致。
- **D4 存储件**：`MemoryEntry`（id/scope/content/created_at @PrePersist）+ Repository（findByScope("CORE") 全量、findRecentArchival 带 LIMIT、searchArchival LIKE）+ schema.sql memory_entries + idx_memory_scope。
- **D5 装配**：Runtime 读 `memory.backend`，按值造对应 store（默认 markdown）；DefaultMemoryService 组合 store+SessionManager；注入 PromptBuilder；MemoryTools 经 registerAnnotated 注册（save_memory/recall_memory 补齐 20 节预留的两工具面）。
- **D6 契约测试与 mem0 替身**：`InMemoryMemoryStore`（内存 Map 实现四契约）既作 mem0 在契约集里的替身、又是测试基建；契约测四档规矩，真实 Mem0 REST 交互由 Mem0MemoryStoreTest 用 mock RestClient 断言请求体带 scope、search 转发。

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| MemoryService/MemoryScope 上移 core（软门禁 1，停点确认） | PromptBuilder(core) 必须注入它；memory→core 依赖已存在，接口留 memory 成环无法编译 | 让 core 依赖 memory（违模块方向、成环）；PromptBuilder 挪出 core（违 §10 落位） |
| PromptBuilder 加构造重载（软门禁 4，停点确认） | 17 节明文留了"②长期记忆"位待 22 节接；注入是唯一接法 | setter 注入（可变状态更差）；旧构造保留=扩展非破坏，17 节测试零改 |
| SqliteMemoryStore 持久化件落 storage 而非 memory（落位表"全部→memory"的细化） | JPA 实体/Repository 按项目惯例归 storage（同 17 节 ToolInvocation）；store 逻辑类仍在 memory | 全塞 memory（与既有分工不一致） |
| memory_entries 表 + memory.backend 配置键（清单外对外概念，停点确认） | 三档中 SQLite 档必需的表、选档必需的配置——课件/§5 已定字面量 | — |
