package io.oryxos.web.controller.dto;

import io.oryxos.core.agent.ScheduledTaskView;
import java.time.Instant;

/** GET /schedules 视图：定时任务状态。 */
public record ScheduleView(
    String scheduleId,
    String profileName,
    String key,
    String name,
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
        t.scheduleId(),
        t.profileName(),
        t.key(),
        t.name(),
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
