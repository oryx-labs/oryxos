package io.oryxos.core.agent;

import java.time.Instant;

/**
 * 定时任务的对外视图：登记信息 + 运行状态（不含实现细节）。供管理台"定时任务"页与 GET /api/v1/schedules。
 *
 * <p>定义来源仍是 skill/Profile 的 schedules；本视图投影自 SQLite 里持久化的任务状态（重启后仍在）。
 */
public record ScheduledTaskView(
    String taskId,
    String profileName,
    String cron,
    String zone,
    String message,
    boolean enabled,
    Instant nextRunAt,
    Instant lastRunAt,
    String lastStatus,
    long runCount) {}
