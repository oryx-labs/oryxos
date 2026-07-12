# Contracts: Memory 可插拔记忆层

**Date**: 2026-07-12 | 消费方：17 节 PromptBuilder（buildContext 注入）、20 节 ToolRegistry（注册记忆工具）、Runtime（按配置装配）

## 1. MemoryService（io.oryxos.core.memory，D1 上移）

```java
public interface MemoryService {
    String buildContext(Session session);              // 长期记忆(核心+归档) + 会话历史，拼进 Prompt
    void remember(String content, MemoryScope scope);   // save_memory 转发
    List<String> recall(String keyword);                // recall_memory 转发
}
public enum MemoryScope { CORE, ARCHIVAL }
```

## 2. LongTermMemoryStore（io.oryxos.memory，可插拔）

```java
public interface LongTermMemoryStore {
    void append(String content, MemoryScope scope);   // scope 路由到核心/归档
    String load();                                     // 核心区全量 + 归档区(截断后)
    List<String> recallByKeyword(String keyword);      // 只搜归档区
}
```

四契约（三档 + 测试替身统一遵守）：①`load` 每次重读不缓存 ②核心区完整、截断只作用归档 ③写入按 scope 分区 ④检索关键词匹配。

## 3. 三档实现（io.oryxos.memory）

- `MarkdownMemoryStore(Path oryxosRoot)`；`SqliteMemoryStore(MemoryEntryRepository)`；`Mem0MemoryStore(RestClient, String userId/baseUrl 经 ${ENV})`。

## 4. 存储件（io.oryxos.storage，SQLite 档）

`MemoryEntry` 实体 + `MemoryEntryRepository`（findByScope 全量核心 / 归档 LIMIT N / LIKE 检索）+ schema.sql `memory_entries`。

## 5. 记忆工具（io.oryxos.memory.builtin，@Tool 管道）

`MemoryTools(MemoryService)`：`save_memory(content, scope=archival 缺省)` / `recall_memory(keyword)`——未命中友好提示、非法 scope 报错。

## 6. PromptBuilder 改造（io.oryxos.core.agent，D2 扩展非破坏）

新增构造 `PromptBuilder(ContextLoader, Map<String,OryxTool>, MemoryService, Clock)`；旧构造保留（memoryService=null，记忆段空）。build() 的"②长期记忆"留位改为注入 `memoryService.buildContext(session)`。

## 7. 装配（OryxOsRuntime）

`memory.backend`（markdown 默认 / sqlite / mem0）→ 造 store → `DefaultMemoryService(store, sessionManager)` bean → 注入 PromptBuilder（新构造）+ `registerAnnotated(new MemoryTools(memoryService))`。
