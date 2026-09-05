package io.oryxos.storage;

import io.oryxos.core.agent.AgentExecution;
import io.oryxos.core.agent.AgentExecutionStore;
import io.oryxos.core.agent.AgentRunEventPayloads;
import io.oryxos.core.agent.TraceContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;

/** {@link AgentExecutionStore} 的 JPA 实现（第 32 节）：写 agent_executions，重启不丢。 */
public class JpaAgentExecutionStore implements AgentExecutionStore {

  private static final int MAX_PREVIEW = 240;

  private final AgentExecutionRepository repository;

  public JpaAgentExecutionStore(AgentExecutionRepository repository) {
    this.repository = repository;
  }

  @Override
  public long start(String agentName, String source, Instant startedAt) {
    return start(agentName, source, startedAt, null);
  }

  @Override
  public long start(String agentName, String source, Instant startedAt, String inputPreview) {
    AgentExecutionEntity e = new AgentExecutionEntity();
    e.setAgentName(agentName);
    e.setSource(source);
    // 021：同两张审计表——trace 走环境读取，Store 契约零改动（triggerAsync 主线程先 open 再 start）
    e.setTraceId(TraceContext.current());
    e.setStartedAt(startedAt);
    e.setUpdatedAt(startedAt);
    e.setStatus("QUEUED");
    e.setInputPreview(truncatePreview(inputPreview));
    return repository.save(e).getId();
  }

  @Override
  public void finish(
      long id, String sessionId, boolean success, String errorMessage, Instant endedAt) {
    finish(id, sessionId, success, errorMessage, endedAt, success ? "SUCCESS" : "FAILED");
  }

  @Override
  public void finish(
      long id,
      String sessionId,
      boolean success,
      String errorMessage,
      Instant endedAt,
      String status) {
    finish(id, sessionId, success, errorMessage, endedAt, status, null);
  }

  @Override
  public void finish(
      long id,
      String sessionId,
      boolean success,
      String errorMessage,
      Instant endedAt,
      String status,
      String stopReason) {
    tryFinish(id, sessionId, success, errorMessage, endedAt, status, stopReason);
  }

  @Override
  public boolean tryFinish(
      long id,
      String sessionId,
      boolean success,
      String errorMessage,
      Instant endedAt,
      String status,
      String stopReason) {
    AgentExecutionEntity e = repository.findById(id).orElse(null);
    if (e == null) {
      return false;
    }
    long durationMs = Duration.between(e.getStartedAt(), endedAt).toMillis();
    return repository.finishIfOpen(
            id, sessionId, success, errorMessage, endedAt, durationMs, status, stopReason)
        == 1;
  }

  @Override
  public List<AgentExecution> listByAgent(String agentName, int limit) {
    return repository
        .findByAgentNameOrderByStartedAtDescIdDesc(agentName, PageRequest.of(0, limit))
        .stream()
        .map(JpaAgentExecutionStore::toView)
        .toList();
  }

  @Override
  public Optional<AgentExecution> findById(long id) {
    return repository.findById(id).map(JpaAgentExecutionStore::toView);
  }

  @Override
  public List<AgentExecution> listRecent(String status, int limit) {
    PageRequest page = PageRequest.of(0, Math.max(1, limit));
    List<AgentExecutionEntity> rows =
        status == null || status.isBlank()
            ? repository.findAllByOrderByStartedAtDescIdDesc(page)
            : repository.findByStatusOrderByStartedAtDescIdDesc(status, page);
    return rows.stream().map(JpaAgentExecutionStore::toView).toList();
  }

  @Override
  public void markRunning(long id, Instant at) {
    repository.markRunningIfOpen(id, at);
  }

  @Override
  public void requestCancel(long id, Instant at) {
    tryRequestCancel(id, at);
  }

  @Override
  public boolean tryRequestCancel(long id, Instant at) {
    return repository.requestCancelIfOpen(id, at) == 1;
  }

  @Override
  public List<AgentExecution> listNonTerminal() {
    return repository.findByStatusIn(List.of("QUEUED", "RUNNING", "CANCELLING")).stream()
        .filter(row -> row.getEndedAt() == null)
        .map(JpaAgentExecutionStore::toView)
        .toList();
  }

  @Override
  public void touchUpdatedAt(long id, Instant at) {
    repository.touchUpdatedAt(id, at);
  }

  private static String truncatePreview(String preview) {
    if (preview == null) {
      return null;
    }
    String summarized = AgentRunEventPayloads.summarizeText(preview.strip());
    if (summarized.length() <= MAX_PREVIEW) {
      return summarized;
    }
    return summarized.substring(0, MAX_PREVIEW) + "…";
  }

  private static AgentExecution toView(AgentExecutionEntity e) {
    return new AgentExecution(
        e.getId(),
        e.getAgentName(),
        e.getSource(),
        e.getSessionId(),
        e.getStartedAt(),
        e.getEndedAt(),
        e.getSuccess(),
        e.getDurationMs(),
        e.getErrorMessage(),
        e.getUpdatedAt(),
        e.getInputPreview(),
        e.getCancelRequestedAt(),
        e.getStatus(),
        e.getStopReason(),
        e.getTraceId());
  }
}
