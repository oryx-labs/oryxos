package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 第 32 节验收 harness：异步触发落"运行中→结束"两阶段记录，成功失败都留痕。 */
class AgentExecutionServiceTest {

  /** 内存假 store，记录 start/finish 调用。 */
  private static final class FakeStore implements AgentExecutionStore {
    final List<AgentExecution> rows = new ArrayList<>();

    @Override
    public synchronized long start(String agentName, String source, Instant startedAt) {
      return start(agentName, source, startedAt, null);
    }

    @Override
    public synchronized long start(
        String agentName, String source, Instant startedAt, String inputPreview) {
      long id = rows.size() + 1;
      rows.add(
          new AgentExecution(
              id,
              agentName,
              source,
              null,
              startedAt,
              null,
              null,
              null,
              null,
              startedAt,
              inputPreview,
              null,
              "QUEUED",
              null));
      return id;
    }

    @Override
    public synchronized void finish(
        long id, String sessionId, boolean success, String errorMessage, Instant endedAt) {
      finish(id, sessionId, success, errorMessage, endedAt, success ? "SUCCESS" : "FAILED", null);
    }

    @Override
    public synchronized void finish(
        long id,
        String sessionId,
        boolean success,
        String errorMessage,
        Instant endedAt,
        String status,
        String stopReason) {
      AgentExecution current = require(id);
      if (current.terminal()) {
        return;
      }
      rows.set(
          indexOf(id),
          new AgentExecution(
              id,
              current.agentName(),
              current.source(),
              sessionId,
              current.startedAt(),
              endedAt,
              success,
              endedAt.toEpochMilli() - current.startedAt().toEpochMilli(),
              errorMessage,
              endedAt,
              current.inputPreview(),
              current.cancelRequestedAt(),
              status,
              stopReason,
              current.traceId()));
    }

    @Override
    public synchronized List<AgentExecution> listByAgent(String agentName, int limit) {
      return List.copyOf(rows);
    }

    @Override
    public synchronized Optional<AgentExecution> findById(long id) {
      return rows.stream().filter(row -> row.id() == id).findFirst();
    }

    @Override
    public synchronized void markRunning(long id, Instant at) {
      AgentExecution current = require(id);
      if (current.terminal()) {
        return;
      }
      rows.set(
          indexOf(id),
          new AgentExecution(
              id,
              current.agentName(),
              current.source(),
              current.sessionId(),
              current.startedAt(),
              current.endedAt(),
              current.success(),
              current.durationMs(),
              current.errorMessage(),
              at,
              current.inputPreview(),
              current.cancelRequestedAt(),
              "RUNNING",
              current.stopReason(),
              current.traceId()));
    }

    @Override
    public synchronized void requestCancel(long id, Instant at) {
      AgentExecution current = require(id);
      if (current.terminal()) {
        return;
      }
      rows.set(
          indexOf(id),
          new AgentExecution(
              id,
              current.agentName(),
              current.source(),
              current.sessionId(),
              current.startedAt(),
              current.endedAt(),
              current.success(),
              current.durationMs(),
              current.errorMessage(),
              at,
              current.inputPreview(),
              at,
              "CANCELLING",
              current.stopReason(),
              current.traceId()));
    }

    @Override
    public synchronized List<AgentExecution> listNonTerminal() {
      return rows.stream().filter(row -> !row.terminal()).toList();
    }

    private AgentExecution require(long id) {
      return findById(id).orElseThrow(() -> new IllegalArgumentException("missing " + id));
    }

    private int indexOf(long id) {
      for (int i = 0; i < rows.size(); i++) {
        if (rows.get(i).id() == id) {
          return i;
        }
      }
      throw new IllegalArgumentException("missing " + id);
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

  @Test
  @DisplayName("无活动工作线程时取消直接收口为 CANCELLED/NO_ACTIVE_WORKER")
  void cancelWithoutWorkerConvergesImmediately() {
    FakeStore store = new FakeStore();
    Instant now = Instant.parse("2026-08-23T04:00:00Z");
    store.start("demo", "manual", now, "检查");
    AgentExecutionService svc =
        new AgentExecutionService(
            store, Executors.newSingleThreadExecutor(), Clock.fixed(now, ZoneOffset.UTC));

    AgentExecution result = svc.cancel(1);

    assertEquals("CANCELLED", result.status());
    assertEquals(AgentStopReasons.NO_ACTIVE_WORKER, result.stopReason());
    assertEquals(AgentStopReasons.MESSAGE_NO_ACTIVE_WORKER, result.errorMessage());
  }

  @Test
  @DisplayName("重复取消终态 Run 保持原状态")
  void cancelIsIdempotentOnTerminalRun() {
    FakeStore store = new FakeStore();
    Instant now = Instant.parse("2026-08-23T04:00:00Z");
    store.start("demo", "manual", now, "检查");
    AgentExecutionService svc =
        new AgentExecutionService(
            store, Executors.newSingleThreadExecutor(), Clock.fixed(now, ZoneOffset.UTC));

    AgentExecution first = svc.cancel(1);
    AgentExecution second = svc.cancel(1);

    assertEquals("CANCELLED", first.status());
    assertEquals("CANCELLED", second.status());
    assertEquals(AgentStopReasons.NO_ACTIVE_WORKER, second.stopReason());
  }

  @Test
  @DisplayName("已经成功结束的 Run 不会被后续取消覆盖")
  void cancelDoesNotOverrideFinishedSuccess() {
    FakeStore store = new FakeStore();
    Instant now = Instant.parse("2026-08-23T04:00:00Z");
    long id = store.start("demo", "manual", now, "检查");
    store.finish(id, "sess", true, null, now.plusSeconds(1), "SUCCESS", null);
    AgentExecutionService svc =
        new AgentExecutionService(
            store, Executors.newSingleThreadExecutor(), Clock.fixed(now, ZoneOffset.UTC));

    AgentExecution result = svc.cancel(id);

    assertEquals("SUCCESS", result.status());
    assertNull(result.stopReason());
  }

  @Test
  @DisplayName("定时 Run 登记当前线程，取消会中断 worker 并在完成边界收口")
  void scheduledRunCancellationInterruptsRegisteredWorker() throws Exception {
    FakeStore store = new FakeStore();
    Instant now = Instant.parse("2026-08-23T04:00:00Z");
    long id = store.start("demo", "schedule", now, "检查");
    AgentExecutionService svc =
        new AgentExecutionService(
            store, Executors.newSingleThreadExecutor(), Clock.fixed(now, ZoneOffset.UTC));
    CountDownLatch attached = new CountDownLatch(1);
    AtomicBoolean interrupted = new AtomicBoolean(false);

    Thread worker =
        Thread.ofVirtual()
            .start(
                () -> {
                  svc.attachScheduledRun(id, "demo", "schedule");
                  attached.countDown();
                  try {
                    Thread.sleep(30_000);
                  } catch (InterruptedException expected) {
                    interrupted.set(true);
                  } finally {
                    svc.completeScheduledRun(id, "schedule-session", false, "interrupted");
                  }
                });

    assertTrue(attached.await(2, TimeUnit.SECONDS));
    AgentExecution accepted = svc.cancel(id);
    worker.join(2_000);

    // interrupt 后 worker 可能在 cancel() 返回前已收口，立即返回值允许 CANCELLING 或 CANCELLED
    assertTrue(
        "CANCELLING".equals(accepted.status()) || "CANCELLED".equals(accepted.status()),
        () -> "取消请求应被接受，实际状态=" + accepted.status());
    assertTrue(interrupted.get());
    AgentExecution terminal = store.findById(id).orElseThrow();
    assertEquals("CANCELLED", terminal.status());
    assertNull(terminal.stopReason());
  }

  @Test
  @DisplayName("启动对账把遗留非终态 Run 收口为 FAILED/PROCESS_RESTARTED，且对终态幂等")
  void reconcileOnStartupFailsLeftoverOpenRuns() {
    FakeStore store = new FakeStore();
    Instant now = Instant.parse("2026-08-23T04:00:00Z");
    store.start("demo", "manual", now, "queued");
    store.start("demo", "manual", now, "running");
    store.markRunning(2, now);
    store.start("demo", "manual", now, "cancelling");
    store.requestCancel(3, now);
    store.start("demo", "manual", now, "done");
    store.finish(4, "sess", true, null, now.plusSeconds(1), "SUCCESS", null);
    AgentExecutionService svc =
        new AgentExecutionService(
            store, Executors.newSingleThreadExecutor(), Clock.fixed(now, ZoneOffset.UTC));

    svc.reconcileOnStartup();
    svc.reconcileOnStartup();

    assertEquals("FAILED", store.findById(1).orElseThrow().status());
    assertEquals(AgentStopReasons.PROCESS_RESTARTED, store.findById(1).orElseThrow().stopReason());
    assertEquals("FAILED", store.findById(2).orElseThrow().status());
    assertEquals("FAILED", store.findById(3).orElseThrow().status());
    assertEquals("SUCCESS", store.findById(4).orElseThrow().status());
    assertNull(store.findById(4).orElseThrow().stopReason());
  }

  @Test
  @DisplayName("达到最大轮次时记 FAILED/MAX_ITERATIONS")
  void maxIterationsBecomesFailedStopReason() throws InterruptedException {
    FakeStore store = new FakeStore();
    ExecutorService ex = Executors.newSingleThreadExecutor();
    AgentExecutionService svc = new AgentExecutionService(store, ex, Clock.systemUTC());

    svc.triggerAsync(
        "demo",
        "manual",
        "sess-3",
        () -> {
          throw new AgentMaxIterationsExceededException(ReActLoop.MAX_ITERATIONS_REPLY);
        });
    await(ex);

    AgentExecution row = store.rows.get(0);
    assertEquals("FAILED", row.status());
    assertEquals(AgentStopReasons.MAX_ITERATIONS, row.stopReason());
    assertEquals(AgentStopReasons.MESSAGE_MAX_ITERATIONS, row.errorMessage());
  }
}
