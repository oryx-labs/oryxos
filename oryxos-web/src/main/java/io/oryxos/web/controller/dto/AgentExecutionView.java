package io.oryxos.web.controller.dto;

import io.oryxos.core.agent.AgentExecution;
import java.time.Instant;

/** GET /agents/{name}/executions 视图：一次执行的起止时间 / 状态 / 时长（第 32 节）。 */
public record AgentExecutionView(
    long id,
    String agentName,
    String source,
    String sessionId,
    Instant startedAt,
    Instant endedAt,
    String status,
    Long durationMs,
    String errorMessage) {

  public static AgentExecutionView from(AgentExecution e) {
    return new AgentExecutionView(
        e.id(),
        e.agentName(),
        e.source(),
        e.sessionId(),
        e.startedAt(),
        e.endedAt(),
        e.status(),
        e.durationMs(),
        e.errorMessage());
  }
}
