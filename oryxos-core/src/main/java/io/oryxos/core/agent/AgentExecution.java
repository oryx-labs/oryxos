package io.oryxos.core.agent;

import java.time.Instant;

/**
 * Agent 维度的一次执行记录（第 32 节）：手动触发 / 定时触发都记，含起止时间与状态。
 *
 * <p>{@code endedAt == null} 表示"运行中"；结束后 {@code success} 才有意义。{@code source} = manual / schedule。
 */
public record AgentExecution(
    long id,
    String agentName,
    String source,
    String sessionId,
    Instant startedAt,
    Instant endedAt,
    Boolean success,
    Long durationMs,
    String errorMessage) {

  /** 运行中 / 成功 / 失败——供前端直接展示。 */
  public String status() {
    if (endedAt == null) {
      return "RUNNING";
    }
    return Boolean.TRUE.equals(success) ? "SUCCESS" : "FAILED";
  }
}
