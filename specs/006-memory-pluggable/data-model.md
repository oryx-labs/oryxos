# Data Model: Memory 可插拔记忆层

**Date**: 2026-07-12 | **Feature**: [spec.md](./spec.md)

## 跨模块契约（oryxos-core，D1 上移）

- `MemoryService`：`String buildContext(Session)` / `void remember(String content, MemoryScope scope)` / `List<String> recall(String keyword)`。
- `MemoryScope`：enum `CORE` / `ARCHIVAL`。

## 后端接口与实现（oryxos-memory）

- `LongTermMemoryStore`：`void append(String content, MemoryScope scope)` / `String load()` / `List<String> recallByKeyword(String keyword)`。
- 四契约（三档共守）：①不缓存 ②核心区永不截断 ③scope 显式路由 ④关键词检索。

| 档 | 存储 | 截断（只归档区） | 检索 |
|---|---|---|---|
| MarkdownMemoryStore | `.oryxos/memory/MEMORY.md`（`## 核心记忆`/`## 归档记忆`） | 字符串裁尾，上限 4000 字符 | `String.contains` 行匹配 |
| SqliteMemoryStore | `memory_entries` 表 | 归档查询 `LIMIT 100` | SQL `LIKE` |
| Mem0MemoryStore | 外部自托管 Mem0 | 交给 Mem0 自管（本档不设本地上限） | Mem0 语义 search |
| InMemoryMemoryStore（测试/mem0 契约替身） | 进程内 Map | 归档 List 保留最近 N | contains |

## 持久化实体（oryxos-storage，SQLite 档）

### memory_entries（手工 schema.sql）

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | INTEGER | PK AUTOINCREMENT | |
| scope | VARCHAR(16) | NOT NULL, 索引 idx_memory_scope | CORE / ARCHIVAL |
| content | TEXT | NOT NULL | 记忆内容 |
| created_at | TIMESTAMP | NOT NULL | @PrePersist |

`MemoryEntryRepository`：`findByScopeOrderByIdAsc("CORE")`（核心全量）、`findByScopeOrderByIdDesc + Pageable(LIMIT)`（归档最近 N）、`searchArchival(LIKE)`。

## 记忆工具（oryxos-memory，@Tool）

- `save_memory(content, scope)`：scope 缺省 `archival`；非法 scope 值报错点名；转发 `MemoryService.remember`。
- `recall_memory(keyword)`：转发 `MemoryService.recall`；未命中返回"没有找到相关记忆"。

## 门面组合（DefaultMemoryService）

- `buildContext(session)` = 长期记忆 `store.load()`（核心区全量 + 归档区截断）+ 会话历史（`SessionManager` / session.messages）。
- `remember` / `recall` 直接转发 `store`。
