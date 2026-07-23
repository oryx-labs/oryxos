package io.oryxos.core.agent;

import java.time.Instant;
import java.util.List;

/**
 * Agent 执行历史持久化（第 32 节）：core 只认这个契约，JPA 实现在 oryxos-storage（依赖倒置，同 {@code ScheduledTaskStore}）。
 *
 * <p>{@link #start} 落一条"运行中"记录并返回主键；{@link #finish} 回填结束时间 / 状态 / 时长。成功失败都记，重启不丢。
 */
public interface AgentExecutionStore {

  /** 开始执行：落一条 ended_at 为空的"运行中"记录，返回主键 id。 */
  long start(String agentName, String source, Instant startedAt);

  /** 结束执行：回填 session、成功与否、错误、结束时间与时长。 */
  void finish(long id, String sessionId, boolean success, String errorMessage, Instant endedAt);

  /** 某 Agent 最近的执行历史（按开始时间倒序，最多 limit 条）。 */
  List<AgentExecution> listByAgent(String agentName, int limit);
}
