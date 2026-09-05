package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentRunEventPublisherTest {

  private static final class MemoryStore implements AgentRunEventStore {
    final List<AgentRunEvent> rows = new ArrayList<>();

    @Override
    public synchronized AgentRunEvent append(
        long runId, String type, String payloadJson, Instant createdAt) {
      AgentRunEvent event =
          new AgentRunEvent(runId, rows.size() + 1L, type, createdAt, payloadJson);
      rows.add(event);
      return event;
    }

    @Override
    public synchronized List<AgentRunEvent> readAfter(long runId, long afterSequence, int limit) {
      return rows.stream()
          .filter(e -> e.runId() == runId && e.sequence() > afterSequence)
          .limit(limit)
          .toList();
    }

    @Override
    public synchronized long lastSequence(long runId) {
      return rows.stream()
          .filter(e -> e.runId() == runId)
          .mapToLong(AgentRunEvent::sequence)
          .max()
          .orElse(0L);
    }
  }

  @Test
  void persistsBeforeHubSeesEvent() {
    MemoryStore store = new MemoryStore();
    AgentRunEventHub hub = new AgentRunEventHub();
    List<AgentRunEvent> seen = new ArrayList<>();
    hub.subscribe(3, seen::add);
    AgentRunEventPublisher publisher =
        new AgentRunEventPublisher(
            store, hub, Clock.fixed(Instant.parse("2026-08-23T04:00:00Z"), ZoneOffset.UTC));

    publisher.publish(3, AgentRunEventTypes.RUN_STARTED, Map.of("agent", "ops"));

    assertEquals(1, store.rows.size());
    assertEquals(1, seen.size());
    assertEquals(store.rows.get(0).sequence(), seen.get(0).sequence());
    assertTrue(store.rows.get(0).payloadJson().contains("schemaVersion"));
  }

  @Test
  void persistFailureIsFailOpen() {
    AgentRunEventStore failing =
        new AgentRunEventStore() {
          @Override
          public AgentRunEvent append(
              long runId, String type, String payloadJson, Instant createdAt) {
            throw new IllegalStateException("disk full");
          }

          @Override
          public List<AgentRunEvent> readAfter(long runId, long afterSequence, int limit) {
            return List.of();
          }

          @Override
          public long lastSequence(long runId) {
            return 0;
          }
        };
    AgentRunEventPublisher publisher =
        new AgentRunEventPublisher(failing, new AgentRunEventHub(), Clock.systemUTC());
    publisher.publish(1, AgentRunEventTypes.RUN_STARTED, Map.of());
  }
}
