# 定时任务身份分离与 SQLite 升级

`AGENT.md` 中的 `schedules` 是定义源。每条任务必须包含 Agent 内唯一的 `key` 和展示用的 `name`；旧 `id` 仅在读取旧配置时兼容为 `key`，并以该值补足 `name`。

运行态不再使用配置键：SQLite 为每个 `(profile_name, schedule_key)` 生成稳定、全局唯一的 UUID `schedule_id`。锁、启停、立即运行、任务状态和执行历史均按 `scheduleId` 工作。因此多个 Agent 可以同时使用 `key: daily`，互不覆盖也不互锁。配置删除或改名时，旧任务会标记为退役并从运行态列表隐藏；其状态和历史仍保留，重新使用同一 key 时会恢复原 ID。已知旧 `scheduleId` 仍可通过 v2 executions 端点回查退役任务的历史，但不能再运行或启停它。

## SQLite 升级

不使用 Flyway 或迁移版本表。启动时 `ScheduleSchemaUpgrade` 根据真实列和约束检测结构：

- 新结构含 `schedule_id` 主键及 `(profile_name, schedule_key)` 唯一约束时，幂等跳过并补齐执行历史索引；
- 识别到旧 `task_id` 双表时，在一个 `BEGIN IMMEDIATE … COMMIT` 内重建、复制并生成 UUID；
- 任何混合或未知结构直接拒绝启动，避免猜测性处理数据。

迁移前必须停止应用并复制数据库文件。旧执行历史保留 `legacy_task_key` 与 `legacy_migrated=true`；若历史无法关联现存旧任务，仍保留记录，`schedule_id` 为 `NULL`。

## API

新管理 API 位于 `/api/v2/schedules`，所有运行态路径参数都是 `scheduleId`。按配置精确定位可使用 `POST /api/v2/agents/{profileName}/schedules/{key}/run`。

v1 仅用于过渡：路径参数作为旧 key 解析。唯一匹配时继续工作；多个 Agent 使用同 key 时返回 HTTP 409，绝不再静默选择第一条。
