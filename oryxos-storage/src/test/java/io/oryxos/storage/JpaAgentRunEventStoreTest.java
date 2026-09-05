package io.oryxos.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.AgentRunEvent;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SqliteJpaTest
class JpaAgentRunEventStoreTest {

  @TempDir static Path dbDir;

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbDir.resolve("run-events.db"));
  }

  @Autowired AgentRunEventRepository repository;

  @Autowired AgentExecutionRepository executionRepository;

  @Test
  void appendIsMonotonicAndReadAfterUsesCursor() {
    JpaAgentRunEventStore store = new JpaAgentRunEventStore(repository);
    Instant now = Instant.parse("2026-08-23T04:00:00Z");
    store.append(7L, "RUN_STARTED", "{\"schemaVersion\":1}", now);
    store.append(7L, "MESSAGE_CONTENT", "{\"delta\":\"hi\"}", now.plusSeconds(1));
    store.append(8L, "RUN_STARTED", "{}", now);

    assertEquals(2, store.lastSequence(7L));
    List<AgentRunEvent> afterFirst = store.readAfter(7L, 1, 10);
    assertEquals(1, afterFirst.size());
    assertEquals(2, afterFirst.get(0).sequence());
    assertEquals("MESSAGE_CONTENT", afterFirst.get(0).type());
    assertTrue(store.readAfter(7L, 2, 10).isEmpty());
  }

  @Test
  void readAfterPagesOneFiveHundredFiveHundredOneAndTwelveHundred() {
    JpaAgentRunEventStore store = new JpaAgentRunEventStore(repository);
    Instant now = Instant.parse("2026-08-23T04:00:00Z");
    for (int i = 0; i < 1200; i++) {
      store.append(9L, "MESSAGE_CONTENT", "{\"n\":" + i + "}", now);
    }

    assertEquals(1, store.readAfter(9L, 0, 1).size());
    assertEquals(1, store.readAfter(9L, 0, 1).get(0).sequence());

    List<AgentRunEvent> first = store.readAfter(9L, 0, 500);
    assertEquals(500, first.size());
    assertEquals(1, first.get(0).sequence());
    assertEquals(500, first.get(499).sequence());

    List<AgentRunEvent> second = store.readAfter(9L, 500, 500);
    assertEquals(500, second.size());
    assertEquals(501, second.get(0).sequence());

    List<AgentRunEvent> last = store.readAfter(9L, 1000, 500);
    assertEquals(200, last.size());
    assertEquals(1200, last.get(199).sequence());
    assertTrue(store.readAfter(9L, 1200, 500).isEmpty());
  }

  @Test
  void onlyFirstConditionalTerminalUpdateWins() {
    AgentExecutionEntity row = new AgentExecutionEntity();
    row.setAgentName("ops");
    row.setSource("manual");
    row.setStartedAt(Instant.parse("2026-08-23T04:00:00Z"));
    row.setUpdatedAt(row.getStartedAt());
    row.setStatus("RUNNING");
    long id = executionRepository.saveAndFlush(row).getId();
    Instant ended = row.getStartedAt().plusSeconds(1);

    int success =
        executionRepository.finishIfOpen(id, "session", true, null, ended, 1000L, "SUCCESS", null);
    int cancelled =
        executionRepository.finishIfOpen(
            id,
            "session",
            false,
            "任务已取消",
            ended.plusMillis(1),
            1001L,
            "CANCELLED",
            "NO_ACTIVE_WORKER");

    assertEquals(1, success);
    assertEquals(0, cancelled);
    AgentExecutionEntity stored = executionRepository.findById(id).orElseThrow();
    assertEquals("SUCCESS", stored.getStatus());
  }
}
