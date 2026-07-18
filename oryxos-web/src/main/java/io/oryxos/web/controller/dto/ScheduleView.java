package io.oryxos.web.controller.dto;

import io.oryxos.core.agent.ScheduledTaskView;
import java.time.Instant;

/** GET /schedules 视图：定时任务状态。 */
public record ScheduleView(
    String taskId,
    String profileName,
    String cron,
    String zone,
    String message,
    boolean enabled,
    Instant nextRunAt,
    Instant lastRunAt,
    String lastStatus,
    long runCount) {

  public static ScheduleView from(ScheduledTaskView t) {
    return new ScheduleView(
        t.taskId(),
        t.profileName(),
        t.cron(),
        t.zone(),
        t.message(),
        t.enabled(),
        t.nextRunAt(),
        t.lastRunAt(),
        t.lastStatus(),
        t.runCount());
  }
}
