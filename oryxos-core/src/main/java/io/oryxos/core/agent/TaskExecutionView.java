package io.oryxos.core.agent;

import java.time.Instant;

/** 定时任务一次执行的历史记录视图（成功失败都记）。供 GET /api/v1/schedules/{id}/executions。 */
public record TaskExecutionView(
    String scheduleId,
    String legacyTaskKey,
    boolean legacyMigrated,
    String sessionId,
    Instant startedAt,
    boolean success,
    String errorMessage,
    long durationMs) {}
