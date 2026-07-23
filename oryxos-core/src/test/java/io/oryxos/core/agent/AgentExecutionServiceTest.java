package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 第 32 节验收 harness：异步触发落"运行中→结束"两阶段记录，成功失败都留痕。 */
class AgentExecutionServiceTest {

  /** 内存假 store，记录 start/finish 调用。 */
  private static final class FakeStore implements AgentExecutionStore {
    final List<AgentExecution> rows = new ArrayList<>();

    @Override
    public synchronized long start(String agentName, String source, Instant startedAt) {
      long id = rows.size() + 1;
      rows.add(new AgentExecution(id, agentName, source, null, startedAt, null, null, null, null));
      return id;
    }

    @Override
    public synchronized void finish(
        long id, String sessionId, boolean success, String errorMessage, Instant endedAt) {
      AgentExecution s = rows.get((int) id - 1);
      rows.set(
          (int) id - 1,
          new AgentExecution(
              id,
              s.agentName(),
              s.source(),
              sessionId,
              s.startedAt(),
              endedAt,
              success,
              endedAt.toEpochMilli() - s.startedAt().toEpochMilli(),
              errorMessage));
    }

    @Override
    public synchronized List<AgentExecution> listByAgent(String agentName, int limit) {
      return List.copyOf(rows);
    }
  }

  private static void await(ExecutorService ex) throws InterruptedException {
    ex.shutdown();
    assertTrue(ex.awaitTermination(5, TimeUnit.SECONDS), "后台任务应在 5s 内跑完");
  }

  @Test
  @DisplayName("triggerAsync：成功 → 记录 RUNNING 起、SUCCESS 止，有时长")
  void triggerAsync_success() throws InterruptedException {
    FakeStore store = new FakeStore();
    ExecutorService ex = Executors.newSingleThreadExecutor();
    AgentExecutionService svc = new AgentExecutionService(store, ex, Clock.systemUTC());

    long id = svc.triggerAsync("demo", "manual", "sess-1", () -> {});
    await(ex);

    assertEquals(1, id);
    AgentExecution row = store.rows.get(0);
    assertEquals("demo", row.agentName());
    assertEquals("manual", row.source());
    assertEquals("sess-1", row.sessionId());
    assertNotNull(row.endedAt());
    assertEquals("SUCCESS", row.status());
    assertTrue(row.success());
    assertNotNull(row.durationMs());
  }

  @Test
  @DisplayName("triggerAsync：work 抛异常 → 记录 FAILED + 错误信息，不外抛")
  void triggerAsync_failure() throws InterruptedException {
    FakeStore store = new FakeStore();
    ExecutorService ex = Executors.newSingleThreadExecutor();
    AgentExecutionService svc = new AgentExecutionService(store, ex, Clock.systemUTC());

    svc.triggerAsync(
        "demo",
        "manual",
        "sess-2",
        () -> {
          throw new IllegalStateException("boom");
        });
    await(ex);

    AgentExecution row = store.rows.get(0);
    assertEquals("FAILED", row.status());
    assertFalse(row.success());
    assertEquals("boom", row.errorMessage());
  }

  @Test
  @DisplayName("Clock 注入不为空（构造可用）")
  void clockUsable() {
    AgentExecutionService svc =
        new AgentExecutionService(
            new FakeStore(),
            Executors.newSingleThreadExecutor(),
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    assertNotNull(svc);
  }
}
