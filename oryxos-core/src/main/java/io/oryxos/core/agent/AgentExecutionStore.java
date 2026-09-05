package io.oryxos.core.agent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Agent 执行历史持久化（第 32 节）：core 只认这个契约，JPA 实现在 oryxos-storage（依赖倒置，同 {@code ScheduledTaskStore}）。
 *
 * <p>{@link #start} 落一条"运行中"记录并返回主键；{@link #finish} 回填结束时间 / 状态 / 时长。成功失败都记，重启不丢。
 */
public interface AgentExecutionStore {

  /** 开始执行：落一条 ended_at 为空的"运行中"记录，返回主键 id。 */
  long start(String agentName, String source, Instant startedAt);

  default long start(String agentName, String source, Instant startedAt, String inputPreview) {
    return start(agentName, source, startedAt);
  }

  /** 结束执行：回填 session、成功与否、错误、结束时间与时长。 */
  void finish(long id, String sessionId, boolean success, String errorMessage, Instant endedAt);

  default void finish(
      long id,
      String sessionId,
      boolean success,
      String errorMessage,
      Instant endedAt,
      String status) {
    finish(id, sessionId, success, errorMessage, endedAt, status, null);
  }

  default void finish(
      long id,
      String sessionId,
      boolean success,
      String errorMessage,
      Instant endedAt,
      String status,
      String stopReason) {
    finish(id, sessionId, success, errorMessage, endedAt);
  }

  /** 尝试形成唯一终态；只有实际完成状态转换的调用方返回 true。生产存储必须原子实现。 */
  default boolean tryFinish(
      long id,
      String sessionId,
      boolean success,
      String errorMessage,
      Instant endedAt,
      String status,
      String stopReason) {
    if (findById(id).map(AgentExecution::terminal).orElse(true)) {
      return false;
    }
    finish(id, sessionId, success, errorMessage, endedAt, status, stopReason);
    return findById(id).map(row -> status.equals(row.status())).orElse(false);
  }

  /** 某 Agent 最近的执行历史（按开始时间倒序，最多 limit 条）。 */
  List<AgentExecution> listByAgent(String agentName, int limit);

  default Optional<AgentExecution> findById(long id) {
    return Optional.empty();
  }

  default List<AgentExecution> listRecent(String status, int limit) {
    return List.of();
  }

  default void markRunning(long id, Instant at) {}

  default void requestCancel(long id, Instant at) {}

  /** 原子接受取消请求；终态或不存在时返回 false。 */
  default boolean tryRequestCancel(long id, Instant at) {
    requestCancel(id, at);
    return findById(id).map(row -> "CANCELLING".equals(row.status())).orElse(false);
  }

  default void touchUpdatedAt(long id, Instant at) {}

  default java.util.List<AgentExecution> listNonTerminal() {
    return java.util.List.of();
  }
}
