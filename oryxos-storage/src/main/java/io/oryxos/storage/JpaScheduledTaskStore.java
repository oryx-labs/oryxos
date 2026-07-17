package io.oryxos.storage;

import io.oryxos.core.agent.ScheduledTaskStore;
import io.oryxos.core.agent.ScheduledTaskView;
import io.oryxos.core.agent.TaskExecutionView;
import java.time.Instant;
import java.util.List;

/**
 * {@link ScheduledTaskStore} 的 JPA 实现（28 节）：任务状态入 scheduled_tasks、执行历史入 task_executions。
 *
 * <p>register 幂等 upsert（已存在则保留 enabled 与 run_count）；recordExecution 写一条历史并更新任务状态。
 */
public class JpaScheduledTaskStore implements ScheduledTaskStore {

  private final ScheduledTaskRepository tasks;
  private final TaskExecutionRepository executions;

  public JpaScheduledTaskStore(ScheduledTaskRepository tasks, TaskExecutionRepository executions) {
    this.tasks = tasks;
    this.executions = executions;
  }

  @Override
  public void register(
      String taskId,
      String profileName,
      String cron,
      String zone,
      String message,
      Instant nextRunAt) {
    ScheduledTask task = tasks.findById(taskId).orElse(null);
    if (task == null) {
      task = new ScheduledTask();
      task.setTaskId(taskId);
      task.setEnabled(true); // 新任务默认启用
      task.setRunCount(0);
    }
    task.setProfileName(profileName);
    task.setCron(cron);
    task.setZone(zone);
    task.setMessage(message);
    task.setNextRunAt(nextRunAt);
    task.setUpdatedAt(Instant.now());
    tasks.save(task);
  }

  @Override
  public void recordExecution(
      String taskId,
      String sessionId,
      Instant startedAt,
      boolean success,
      String errorMessage,
      long durationMs,
      Instant nextRunAt) {
    TaskExecution exec = new TaskExecution();
    exec.setTaskId(taskId);
    exec.setSessionId(sessionId);
    exec.setStartedAt(startedAt);
    exec.setSuccess(success);
    exec.setErrorMessage(errorMessage);
    exec.setDurationMs(durationMs);
    executions.save(exec);

    tasks
        .findById(taskId)
        .ifPresent(
            task -> {
              task.setLastRunAt(startedAt);
              task.setLastStatus(success ? "success" : "failed");
              task.setRunCount(task.getRunCount() + 1);
              task.setNextRunAt(nextRunAt);
              task.setUpdatedAt(Instant.now());
              tasks.save(task);
            });
  }

  @Override
  public boolean isEnabled(String taskId) {
    return tasks.findById(taskId).map(ScheduledTask::isEnabled).orElse(true); // fail-open
  }

  @Override
  public void setEnabled(String taskId, boolean enabled) {
    tasks
        .findById(taskId)
        .ifPresent(
            task -> {
              task.setEnabled(enabled);
              task.setUpdatedAt(Instant.now());
              tasks.save(task);
            });
  }

  @Override
  public List<ScheduledTaskView> list() {
    return tasks.findAll().stream().map(JpaScheduledTaskStore::toTaskView).toList();
  }

  @Override
  public List<TaskExecutionView> executions(String taskId, int limit) {
    return executions.findByTaskIdOrderByStartedAtDesc(taskId).stream()
        .limit(limit)
        .map(JpaScheduledTaskStore::toExecutionView)
        .toList();
  }

  private static ScheduledTaskView toTaskView(ScheduledTask t) {
    return new ScheduledTaskView(
        t.getTaskId(),
        t.getProfileName(),
        t.getCron(),
        t.getZone(),
        t.getMessage(),
        t.isEnabled(),
        t.getNextRunAt(),
        t.getLastRunAt(),
        t.getLastStatus(),
        t.getRunCount());
  }

  private static TaskExecutionView toExecutionView(TaskExecution e) {
    return new TaskExecutionView(
        e.getTaskId(),
        e.getSessionId(),
        e.getStartedAt(),
        e.isSuccess(),
        e.getErrorMessage(),
        e.getDurationMs());
  }
}
