package io.oryxos.core.agent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 执行编排（第 32 节）：异步触发 + 执行历史。
 *
 * <p>异步触发（{@link #triggerAsync}）先落一条记录、立即返回 id（HTTP 请求不再干等整轮 ReAct，杜绝浏览器 Failed to fetch），真正的 ReAct
 * 在虚拟线程后台跑，结束回填状态——符合宪法 VII（虚拟线程处理并发，非 Reactor/WebFlux）。成功失败都留痕（宪法 V）。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "CRLF_INJECTION_LOGS",
    justification = "日志消息中唯一动态部分是 agentName，已通过 sanitize() 去除 CRLF——不存在注入风险。")
public class AgentExecutionService {

  private static final Logger LOG = LoggerFactory.getLogger(AgentExecutionService.class);

  private static final String STATUS_SUCCESS = "SUCCESS";
  private static final String STATUS_FAILED = "FAILED";
  private static final String STATUS_CANCELLED = "CANCELLED";

  private final AgentExecutionStore store;
  private final ExecutorService executor;
  private final Clock clock;
  private final AgentRunEventPublisher events;
  private final ConcurrentHashMap<Long, Thread> runningThreads = new ConcurrentHashMap<>();

  public AgentExecutionService(AgentExecutionStore store, ExecutorService executor, Clock clock) {
    this(store, executor, clock, null);
  }

  public AgentExecutionService(
      AgentExecutionStore store,
      ExecutorService executor,
      Clock clock,
      AgentRunEventPublisher events) {
    this.store = store;
    this.executor = executor;
    this.clock = clock;
    this.events = events;
  }

  /**
   * 异步触发一次 Agent 执行：落记录 → 立即返回 id → {@code work} 在虚拟线程后台执行 → 结束回填。 {@code work} 内部即完整的一轮编排（{@code
   * AgentService.process}，审计在其内部）。
   */
  public long triggerAsync(String agentName, String source, String sessionId, Runnable work) {
    return triggerAsync(agentName, source, sessionId, null, work);
  }

  public long triggerAsync(
      String agentName, String source, String sessionId, String inputPreview, Runnable work) {
    // 021 唯一跨线程传递点（R4）：主线程生成 trace → 落执行记录（store 自读上下文）→ 显式传入后台虚拟线程。
    // ThreadLocal 不跨线程，靠闭包捕获的 traceId 在后台线程重新置入，work 内 AgentService 兜底 openIfAbsent 复用同值。
    final String traceId;
    final long id;
    try (TraceContext.Scope scope = TraceContext.openIfAbsent()) {
      traceId = scope.traceId();
      id = store.start(agentName, source, clock.instant(), inputPreview);
    }
    executor.execute(
        () -> {
          try (TraceContext.Scope scope = TraceContext.open(traceId)) {
            runInContext(id, agentName, source, sessionId, work);
          }
        });
    return id;
  }

  public List<AgentExecution> history(String agentName, int limit) {
    return store.listByAgent(agentName, limit);
  }

  public List<AgentExecution> listRecent(String status, int limit) {
    return store.listRecent(status, limit);
  }

  public Optional<AgentExecution> findById(long id) {
    return store.findById(id);
  }

  public AgentExecution cancel(long id) {
    AgentExecution current =
        store.findById(id).orElseThrow(() -> new IllegalArgumentException("Run 不存在: " + id));
    if (current.terminal()) {
      AgentRunExecutionContext.clearCancel(id);
      return current;
    }
    Instant now = clock.instant();
    if (!store.tryRequestCancel(id, now)) {
      return store.findById(id).orElse(current);
    }
    AgentRunExecutionContext.requestCancel(id);
    publish(id, AgentRunEventTypes.RUN_CANCELLING, Map.of("requestedAt", now.toString()));
    AgentExecution accepted = store.findById(id).orElse(current);
    Thread worker = runningThreads.get(id);
    if (worker != null) {
      worker.interrupt();
      // 先返回 CANCELLING 快照：worker 可能在 interrupt 后立刻收口为 CANCELLED
      return accepted;
    }
    finishCancelled(id, current.sessionId(), AgentStopReasons.NO_ACTIVE_WORKER);
    return store.findById(id).orElse(current);
  }

  /** 启动时把本进程无法证明仍在执行的非终态 Run 收敛为失败。 */
  public void reconcileOnStartup() {
    Instant now = clock.instant();
    for (AgentExecution row : store.listNonTerminal()) {
      if (runningThreads.containsKey(row.id())) {
        continue;
      }
      if (store.tryFinish(
          row.id(),
          row.sessionId(),
          false,
          AgentStopReasons.MESSAGE_PROCESS_RESTARTED,
          now,
          STATUS_FAILED,
          AgentStopReasons.PROCESS_RESTARTED)) {
        publish(
            row.id(),
            AgentRunEventTypes.RUN_FAILED,
            Map.of(
                "status",
                STATUS_FAILED,
                "error",
                AgentStopReasons.MESSAGE_PROCESS_RESTARTED,
                "stopReason",
                AgentStopReasons.PROCESS_RESTARTED,
                "durationMs",
                durationOf(row.id(), now)));
      }
    }
  }

  public void attachScheduledRun(long id, String agentName, String source) {
    runningThreads.put(id, Thread.currentThread());
    AgentRunExecutionContext.set(id);
    store.markRunning(id, clock.instant());
    publish(id, AgentRunEventTypes.RUN_STARTED, Map.of("agent", agentName, "source", source));
  }

  public void completeScheduledRun(long id, String sessionId, boolean success, String error) {
    try {
      if (store.findById(id).map(AgentExecution::terminal).orElse(false)) {
        return;
      }
      if (AgentRunExecutionContext.isCancelRequested(id)) {
        finishCancelled(id, sessionId, null);
      } else if (success) {
        finishSuccess(id, sessionId);
      } else if (isMaxIterationsError(error)) {
        finishFailed(id, sessionId, error, AgentStopReasons.MAX_ITERATIONS);
      } else {
        finishFailed(id, sessionId, error, null);
      }
    } finally {
      runningThreads.remove(id, Thread.currentThread());
      AgentRunExecutionContext.clear();
    }
  }

  private void runInContext(
      long id, String agentName, String source, String sessionId, Runnable work) {
    runningThreads.put(id, Thread.currentThread());
    AgentRunExecutionContext.set(id);
    boolean ok = false;
    String error = null;
    boolean cancelled = false;
    try {
      if (store.findById(id).map(AgentExecution::terminal).orElse(true)) {
        return;
      }
      store.markRunning(id, clock.instant());
      publish(id, AgentRunEventTypes.RUN_STARTED, Map.of("agent", agentName, "source", source));
      if (AgentRunExecutionContext.isCancelRequested()) {
        throw new RunCancelledException();
      }
      work.run();
      if (AgentRunExecutionContext.isCancelRequested()) {
        throw new RunCancelledException();
      }
      ok = true;
    } catch (RunCancelledException e) {
      cancelled = true;
      error = e.getMessage();
    } catch (AgentMaxIterationsExceededException e) {
      error = e.getMessage();
      LOG.warn("Agent {} 达到最大轮次", sanitize(agentName));
      finishFailed(id, sessionId, error, AgentStopReasons.MAX_ITERATIONS);
    } catch (RuntimeException e) {
      if (AgentRunExecutionContext.isCancelRequested() || Thread.currentThread().isInterrupted()) {
        cancelled = true;
        error = "任务已取消";
      } else {
        error = e.getMessage();
        LOG.error("Agent " + sanitize(agentName) + " 后台执行失败", e);
      }
    } finally {
      try {
        if (!store.findById(id).map(AgentExecution::terminal).orElse(false)) {
          if (cancelled) {
            finishCancelled(id, sessionId, null);
          } else if (ok) {
            finishSuccess(id, sessionId);
          } else {
            finishFailed(id, sessionId, error, null);
          }
        }
      } finally {
        runningThreads.remove(id);
        AgentRunExecutionContext.clear();
      }
    }
  }

  private void finishSuccess(long id, String sessionId) {
    Instant ended = clock.instant();
    AgentRunExecutionContext.clearCancel(id);
    if (store.tryFinish(id, sessionId, true, null, ended, STATUS_SUCCESS, null)) {
      publish(
          id,
          AgentRunEventTypes.RUN_FINISHED,
          Map.of("status", STATUS_SUCCESS, "durationMs", durationOf(id, ended)));
    }
  }

  private void finishFailed(long id, String sessionId, String error, String stopReason) {
    Instant ended = clock.instant();
    AgentRunExecutionContext.clearCancel(id);
    String message =
        AgentStopReasons.MAX_ITERATIONS.equals(stopReason)
            ? AgentStopReasons.MESSAGE_MAX_ITERATIONS
            : AgentRunEventPayloads.summarizeText(error);
    if (!store.tryFinish(id, sessionId, false, message, ended, STATUS_FAILED, stopReason)) {
      return;
    }
    java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
    payload.put("status", STATUS_FAILED);
    payload.put("error", message);
    payload.put("durationMs", durationOf(id, ended));
    if (stopReason != null) {
      payload.put("stopReason", stopReason);
    }
    publish(id, AgentRunEventTypes.RUN_FAILED, payload);
  }

  private void finishCancelled(long id, String sessionId, String stopReason) {
    Instant ended = clock.instant();
    AgentRunExecutionContext.clearCancel(id);
    if (!store.tryFinish(
        id,
        sessionId,
        false,
        AgentStopReasons.MESSAGE_NO_ACTIVE_WORKER,
        ended,
        STATUS_CANCELLED,
        stopReason)) {
      return;
    }
    java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
    payload.put("durationMs", durationOf(id, ended));
    if (stopReason != null) {
      payload.put("stopReason", stopReason);
    }
    publish(id, AgentRunEventTypes.RUN_CANCELLED, payload);
  }

  private long durationOf(long id, Instant ended) {
    return store
        .findById(id)
        .map(row -> Duration.between(row.startedAt(), ended).toMillis())
        .orElse(0L);
  }

  private void publish(long id, String type, Map<String, Object> payload) {
    if (events == null) {
      return;
    }
    events.publish(id, type, payload);
  }

  private static boolean isMaxIterationsError(String error) {
    if (error == null) {
      return false;
    }
    return AgentStopReasons.MESSAGE_MAX_ITERATIONS.equals(error) || error.contains("达到最大轮数");
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
