package io.oryxos.web.controller.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.agent.AgentRunEvent;
import java.time.Instant;

/** Run Event 对外视图。 */
public record AgentRunEventView(
    int schemaVersion,
    long runId,
    long sequence,
    String type,
    Instant createdAt,
    JsonNode payload) {

  private static final String SCHEMA_VERSION_FIELD = "schemaVersion";

  public static AgentRunEventView from(AgentRunEvent event, JsonNode payload) {
    int version = 1;
    if (payload != null && payload.has(SCHEMA_VERSION_FIELD)) {
      version = payload.get(SCHEMA_VERSION_FIELD).asInt(1);
    }
    return new AgentRunEventView(
        version, event.runId(), event.sequence(), event.type(), event.createdAt(), payload);
  }
}
