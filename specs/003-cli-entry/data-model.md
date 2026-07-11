# Data Model: CLI——OryxOS 的命令行入口

**Date**: 2026-07-10 | **Feature**: [spec.md](./spec.md)

## 持久化实体（oryxos-storage）

### sessions（手工 schema.sql，宪法 VIII）

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| session_id | VARCHAR | PRIMARY KEY | `channel:user:profile`（拼接只在 JpaSessionManager） |
| profile_name | VARCHAR | NOT NULL, 索引 idx_sessions_profile | 关联 Profile |
| channel | VARCHAR | NOT NULL | 接入渠道（cli/web/scheduler…） |
| user_id | VARCHAR | NOT NULL | 用户标识 |
| messages_json | TEXT | | 对话历史整体 JSON 序列化（List\<Message\>） |
| status | VARCHAR | NOT NULL | `active`（本节唯一取值）/ `archived`（26 节写入） |
| created_at | TIMESTAMP | NOT NULL | 创建时间（@PrePersist） |
| last_active_at | TIMESTAMP | | 最后活跃（save 时刷新） |
| archived_at | TIMESTAMP | 可空 | 归档时间（本节恒空） |

`io.oryxos.storage.Session` 实体字段与列一一对应（与 core 领域对象同名不同包，JpaSessionManager 内全限定引用实体、import core 类型）。

### messages_json 内容格式

`List<Message>` 直接序列化：`[{"role":"user","content":"...","toolName":null}, {"role":"assistant",...}, {"role":"tool","content":"...","toolName":"http_get"}]`——与 core `Message` record 字段一一对应，回读用 `TypeReference<List<Message>>`。

## 领域对象改造（oryxos-core，17 节预告改造点）

- `SessionManager` 接口补全：`Session getOrCreate(String channel, String userId, String profileName)` / `Optional<Session> get(String sessionId)` / `void save(Session session)`（原有）。
- `Session` 新增恢复构造器 `Session(String sessionId, String profileName, List<Message> restored)`；既有构造器与 append 三兄弟不动。

## 状态与生命周期

- 会话：getOrCreate 未命中 → 新建（status=active、created_at）→ 对话累积（内存）→ save（messages_json 覆盖、last_active_at 刷新）→（26 节）DELETE 归档 status=archived + archived_at。
- 幂等兜底：主键即三元组拼接结果，并发 getOrCreate 撞主键 = 已存在，不会产生第二条。

## 命令面（12 个，oryxos-cli）

| 命令 | 轻/重 | 数据访问 |
|---|---|---|
| init | 轻 | Files 建 `.oryxos/` 骨架（幂等，不覆盖已有） |
| status | 轻 | Files 检查工作区/配置/库文件存在性 |
| profile list/create/show/delete | 轻 | Files 读写 `.oryxos/profiles/*.yaml` |
| provider list | 轻 | SnakeYAML 直读 application.yml 的 oryxos.providers |
| tool list | 轻 | 输出内置工具清单占位（20 节 ToolRegistry 接线后改查注册表） |
| session list | 轻 | 纯 JDBC 只读查 sessions 表（库不存在→"暂无会话"） |
| chat | 重 | Spring 全链装配 → CliChannel 交互 |
| serve / gateway | 重 | 启动骨架：起上下文常驻（REST 端点 26 节、多通道挂载扩展阶段） |
