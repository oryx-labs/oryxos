package io.oryxos.core.agent;

import java.time.Instant;
import java.util.List;

/** append-only Run Event 存储：先落库再允许 SSE 观察到。 */
public interface AgentRunEventStore {

  AgentRunEvent append(long runId, String type, String payloadJson, Instant createdAt);

  List<AgentRunEvent> readAfter(long runId, long afterSequence, int limit);

  long lastSequence(long runId);
}
