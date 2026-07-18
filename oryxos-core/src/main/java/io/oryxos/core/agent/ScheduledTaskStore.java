package io.oryxos.core.agent;

import java.time.Instant;
import java.util.List;

/**
 * 定时任务的持久化契约（28 节）：把任务状态与执行历史落 SQLite，重启后仍在。
 *
 * <p>依赖倒置：接口在 core（{@link AgentScheduler} 用它），JPA 实现在 oryxos-storage。定义来源仍是 skill/Profile 的
 * schedules——本 store 只存"状态 + 历史"，不作为定义源（与 29 节 skill-centric 不冲突）。
 */
public interface ScheduledTaskStore {

  /** 注册/更新一条任务的登记信息与下次触发（启动扫描或运行时新增时调用）；新任务默认启用。 */
  void register(
      String taskId,
      String profileName,
      String cron,
      String zone,
      String message,
      Instant nextRunAt);

  /** 记录一次执行（成功失败都记），并更新任务的 last_run / last_status / run_count / next_run。 */
  void recordExecution(
      String taskId,
      String sessionId,
      Instant startedAt,
      boolean success,
      String errorMessage,
      long durationMs,
      Instant nextRunAt);

  /** 任务是否启用（未登记的按启用处理，fail-open）。 */
  boolean isEnabled(String taskId);

  /** 启用/停用一条任务（管理台开关）。 */
  void setEnabled(String taskId, boolean enabled);

  /** 列出全部定时任务的状态视图。 */
  List<ScheduledTaskView> list();

  /** 某任务最近 {@code limit} 条执行历史（按开始时间倒序）。 */
  List<TaskExecutionView> executions(String taskId, int limit);
}
