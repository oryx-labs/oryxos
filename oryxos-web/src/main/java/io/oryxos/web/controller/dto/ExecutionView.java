package io.oryxos.web.controller.dto;

import io.oryxos.core.agent.TaskExecutionView;
import java.time.Instant;

/** GET /schedules/{id}/executions 视图：一次执行历史。 */
public record ExecutionView(
    String scheduleId,
    String legacyTaskKey,
    boolean legacyMigrated,
    String sessionId,
    Instant startedAt,
    boolean success,
    String errorMessage,
    long durationMs) {

  public static ExecutionView from(TaskExecutionView e) {
    return new ExecutionView(
        e.scheduleId(),
        e.legacyTaskKey(),
        e.legacyMigrated(),
        e.sessionId(),
        e.startedAt(),
        e.success(),
        e.errorMessage(),
        e.durationMs());
  }
}
