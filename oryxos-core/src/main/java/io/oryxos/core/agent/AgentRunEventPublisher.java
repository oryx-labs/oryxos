package io.oryxos.core.agent;

import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 先持久化再唤醒订阅者。发布失败 fail-open：不得让 ReAct 或工具把这次当失败、更不得重试外部副作用。 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"CRLF_INJECTION_LOGS", "EI_EXPOSE_REP2"},
    justification = "日志中的 type/err 已 sanitize 去掉 CR/LF，taint 分析不跨方法追踪；store/hub 是运行时共享单例，不能防御性拷贝。")
public class AgentRunEventPublisher {

  private static final Logger LOG = LoggerFactory.getLogger(AgentRunEventPublisher.class);

  private final AgentRunEventStore store;
  private final AgentRunEventHub hub;
  private final Clock clock;

  public AgentRunEventPublisher(AgentRunEventStore store, AgentRunEventHub hub, Clock clock) {
    this.store = store;
    this.hub = hub;
    this.clock = clock == null ? Clock.systemUTC() : clock;
  }

  public void publish(long runId, String type, Map<String, Object> payload) {
    try {
      AgentRunEvent event =
          store.append(runId, type, AgentRunEventPayloads.json(payload), clock.instant());
      hub.emit(event);
    } catch (RuntimeException e) {
      LOG.warn(
          "Run event 发布失败（不影响执行）：runId={} type={} err={}",
          runId,
          sanitize(type),
          sanitize(e.getMessage()));
    }
  }

  public void publishCurrent(String type, Map<String, Object> payload) {
    Long runId = AgentRunExecutionContext.currentRunId();
    if (runId == null) {
      return;
    }
    publish(runId, type, payload);
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
