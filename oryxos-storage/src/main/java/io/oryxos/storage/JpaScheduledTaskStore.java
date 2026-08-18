package io.oryxos.storage;

import io.oryxos.core.agent.ScheduledTaskStore;
import io.oryxos.core.agent.ScheduledTaskView;
import io.oryxos.core.agent.TaskExecutionView;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** JPA-backed runtime state and execution history for configured schedules. */
public class JpaScheduledTaskStore implements ScheduledTaskStore {

  private final ScheduledTaskRepository tasks;
  private final TaskExecutionRepository executions;

  public JpaScheduledTaskStore(ScheduledTaskRepository tasks, TaskExecutionRepository executions) {
    this.tasks = tasks;
    this.executions = executions;
  }

  @Override
  public String reconcile(
      String profileName,
      String key,
      String name,
      String cron,
      String zone,
      String message,
      Instant nextRunAt) {
    ScheduledTask task = tasks.findByProfileNameAndScheduleKey(profileName, key).orElse(null);
    if (task == null) {
      task = new ScheduledTask();
      task.setScheduleId(UUID.randomUUID().toString());
      task.setProfileName(profileName);
      task.setScheduleKey(key);
      task.setEnabled(true);
      task.setRunCount(0);
    }
    task.setRetired(false);
    task.setDisplayName(name);
    task.setCron(cron);
    task.setZone(zone);
    task.setMessage(message);
    task.setNextRunAt(nextRunAt);
    task.setUpdatedAt(Instant.now());
    tasks.save(task);
    return task.getScheduleId();
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void recordExecution(
      String scheduleId,
      String sessionId,
      Instant startedAt,
      boolean success,
      String errorMessage,
      long durationMs,
      Instant nextRunAt) {
    ScheduledTask task = requireSchedule(scheduleId);
    TaskExecution execution = new TaskExecution();
    execution.setScheduleId(scheduleId);
    execution.setLegacyMigrated(false);
    execution.setSessionId(sessionId);
    execution.setStartedAt(startedAt);
    execution.setSuccess(success);
    execution.setErrorMessage(errorMessage);
    execution.setDurationMs(durationMs);
    executions.save(execution);

    task.setLastRunAt(startedAt);
    task.setLastStatus(success ? "success" : "failed");
    task.setRunCount(task.getRunCount() + 1);
    task.setNextRunAt(nextRunAt);
    task.setUpdatedAt(Instant.now());
    tasks.save(task);
  }

  @Override
  public boolean isEnabled(String scheduleId) {
    ScheduledTask task = requireSchedule(scheduleId);
    return !task.isRetired() && task.isEnabled();
  }

  @Override
  public void setEnabled(String scheduleId, boolean enabled) {
    ScheduledTask task = requireSchedule(scheduleId);
    task.setEnabled(enabled);
    task.setUpdatedAt(Instant.now());
    tasks.save(task);
  }

  @Override
  public void retire(String profileName, String key) {
    tasks
        .findByProfileNameAndScheduleKey(profileName, key)
        .ifPresent(
            task -> {
              task.setRetired(true);
              task.setUpdatedAt(Instant.now());
              tasks.save(task);
            });
  }

  @Override
  public List<ScheduledTaskView> list() {
    return tasks.findByRetiredFalse().stream().map(JpaScheduledTaskStore::toTaskView).toList();
  }

  @Override
  public List<ScheduledTaskView> findByKey(String key) {
    return tasks.findByScheduleKeyAndRetiredFalse(key).stream()
        .map(JpaScheduledTaskStore::toTaskView)
        .toList();
  }

  @Override
  public boolean exists(String scheduleId) {
    return tasks.existsById(scheduleId);
  }

  @Override
  public List<TaskExecutionView> executions(String scheduleId, int limit) {
    requireSchedule(scheduleId);
    return executions.findByScheduleIdOrderByStartedAtDesc(scheduleId).stream()
        .limit(limit)
        .map(JpaScheduledTaskStore::toExecutionView)
        .toList();
  }

  private static ScheduledTaskView toTaskView(ScheduledTask task) {
    return new ScheduledTaskView(
        task.getScheduleId(),
        task.getProfileName(),
        task.getScheduleKey(),
        task.getDisplayName(),
        task.getCron(),
        task.getZone(),
        task.getMessage(),
        task.isEnabled(),
        task.getNextRunAt(),
        task.getLastRunAt(),
        task.getLastStatus(),
        task.getRunCount());
  }

  private static TaskExecutionView toExecutionView(TaskExecution execution) {
    return new TaskExecutionView(
        execution.getScheduleId(),
        execution.getLegacyTaskKey(),
        execution.isLegacyMigrated(),
        execution.getSessionId(),
        execution.getStartedAt(),
        execution.isSuccess(),
        execution.getErrorMessage(),
        execution.getDurationMs());
  }

  private ScheduledTask requireSchedule(String scheduleId) {
    return tasks
        .findById(scheduleId)
        .orElseThrow(() -> new NoSuchElementException("Unknown scheduleId: " + scheduleId));
  }
}
