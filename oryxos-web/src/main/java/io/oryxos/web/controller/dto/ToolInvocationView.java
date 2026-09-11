package io.oryxos.web.controller.dto;

import io.oryxos.storage.ToolInvocation;
import java.time.Instant;

/** 工具调用明细（下钻）；traceId（021）为行级 trace 维度入口，旧行为 null。 */
public record ToolInvocationView(
    Long id,
    String profileName,
    String toolName,
    boolean success,
    String blockedBy,
    String executionBackend,
    String containerId,
    long durationMs,
    Instant createdAt,
    String traceId) {

  public static ToolInvocationView from(ToolInvocation t) {
    return new ToolInvocationView(
        t.getId(),
        t.getProfileName(),
        t.getToolName(),
        t.isSuccess(),
        t.getBlockedBy(),
        t.getExecutionBackend(),
        t.getContainerId(),
        t.getDurationMs(),
        t.getCreatedAt(),
        t.getTraceId());
  }
}
