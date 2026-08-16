package io.oryxos.web.controller.dto;

import io.oryxos.core.agent.ScheduledTaskView;
import java.time.Instant;

/** Compatibility projection returned by the deprecated v1 schedules endpoint. */
public record LegacyScheduleView(
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

  public static LegacyScheduleView from(ScheduledTaskView task) {
    return new LegacyScheduleView(
        task.key(),
        task.profileName(),
        task.cron(),
        task.zone(),
        task.message(),
        task.enabled(),
        task.nextRunAt(),
        task.lastRunAt(),
        task.lastStatus(),
        task.runCount());
  }
}
