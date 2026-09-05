package io.oryxos.web.controller.dto;

import io.oryxos.core.agent.AgentExecution;
import java.time.Instant;

/** Run 快照：复用 AgentExecution 主键。 */
public record AgentRunView(
    long id,
    String agentName,
    String source,
    String sessionId,
    String inputPreview,
    String status,
    Instant startedAt,
    Instant updatedAt,
    Instant endedAt,
    Long durationMs,
    String errorMessage,
    String stopReason,
    long lastSequence,
    boolean cancellable) {

  public static AgentRunView from(AgentExecution execution, long lastSequence) {
    return new AgentRunView(
        execution.id(),
        execution.agentName(),
        execution.source(),
        execution.sessionId(),
        execution.inputPreview(),
        execution.status(),
        execution.startedAt(),
        execution.lastUpdatedAt(),
        execution.endedAt(),
        execution.durationMs(),
        execution.errorMessage(),
        execution.stopReason(),
        lastSequence,
        execution.cancellable());
  }
}
