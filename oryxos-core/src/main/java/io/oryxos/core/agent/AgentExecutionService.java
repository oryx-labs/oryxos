package io.oryxos.core.agent;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 执行编排（第 32 节）：异步触发 + 执行历史。
 *
 * <p>异步触发（{@link #triggerAsync}）先落一条"运行中"记录、立即返回 id（HTTP 请求不再干等整轮 ReAct，杜绝浏览器 Failed to fetch），真正的
 * ReAct 在虚拟线程后台跑，结束回填状态——符合宪法 VII（虚拟线程处理并发，非 Reactor/WebFlux）。成功失败都留痕（宪法 V）。
 */
public class AgentExecutionService {

  private static final Logger LOG = LoggerFactory.getLogger(AgentExecutionService.class);

  private final AgentExecutionStore store;
  private final ExecutorService executor;
  private final Clock clock;

  public AgentExecutionService(AgentExecutionStore store, ExecutorService executor, Clock clock) {
    this.store = store;
    this.executor = executor;
    this.clock = clock;
  }

  /**
   * 异步触发一次 Agent 执行：落"运行中"记录 → 立即返回 id → {@code work} 在虚拟线程后台执行 → 结束回填。 {@code work}
   * 内部即完整的一轮编排（{@code AgentService.process}，审计在其内部）。
   */
  public long triggerAsync(String agentName, String source, String sessionId, Runnable work) {
    long id = store.start(agentName, source, clock.instant());
    executor.execute(
        () -> {
          boolean ok = false;
          String error = null;
          try {
            work.run();
            ok = true;
          } catch (RuntimeException e) {
            error = e.getMessage();
            LOG.error("Agent 后台执行失败", e);
          } finally {
            safeFinish(id, sessionId, ok, error);
          }
        });
    return id;
  }

  public List<AgentExecution> history(String agentName, int limit) {
    return store.listByAgent(agentName, limit);
  }

  private void safeFinish(long id, String sessionId, boolean success, String error) {
    try {
      store.finish(id, sessionId, success, error, clock.instant());
    } catch (RuntimeException e) {
      LOG.warn("Agent 执行记录回填失败（id={}）：{}", id, sanitize(e.getMessage()));
    }
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
