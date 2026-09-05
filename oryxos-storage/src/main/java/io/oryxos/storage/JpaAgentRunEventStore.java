package io.oryxos.storage;

import io.oryxos.core.agent.AgentRunEvent;
import io.oryxos.core.agent.AgentRunEventStore;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;

/** {@link AgentRunEventStore} 的 JPA 实现：同一 Run 内 sequence 单调递增。 */
public class JpaAgentRunEventStore implements AgentRunEventStore {

  private final AgentRunEventRepository repository;

  public JpaAgentRunEventStore(AgentRunEventRepository repository) {
    this.repository = repository;
  }

  @Override
  public synchronized AgentRunEvent append(
      long runId, String type, String payloadJson, Instant createdAt) {
    Long max = repository.maxSequence(runId);
    long sequence = (max == null ? 0L : max) + 1;
    AgentRunEventEntity entity = new AgentRunEventEntity();
    entity.setRunId(runId);
    entity.setSequence(sequence);
    entity.setType(type);
    entity.setCreatedAt(createdAt);
    entity.setPayloadJson(payloadJson == null ? "{}" : payloadJson);
    AgentRunEventEntity saved = repository.saveAndFlush(entity);
    return toView(saved);
  }

  @Override
  public List<AgentRunEvent> readAfter(long runId, long afterSequence, int limit) {
    return repository
        .findByRunIdAndSequenceGreaterThanOrderBySequenceAsc(
            runId, afterSequence, PageRequest.of(0, Math.max(1, limit)))
        .stream()
        .map(JpaAgentRunEventStore::toView)
        .toList();
  }

  @Override
  public long lastSequence(long runId) {
    Long max = repository.maxSequence(runId);
    return max == null ? 0L : max;
  }

  private static AgentRunEvent toView(AgentRunEventEntity e) {
    return new AgentRunEvent(
        e.getRunId(), e.getSequence(), e.getType(), e.getCreatedAt(), e.getPayloadJson());
  }
}
