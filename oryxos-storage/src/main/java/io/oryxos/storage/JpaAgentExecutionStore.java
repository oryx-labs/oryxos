package io.oryxos.storage;

import io.oryxos.core.agent.AgentExecution;
import io.oryxos.core.agent.AgentExecutionStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;

/** {@link AgentExecutionStore} 的 JPA 实现（第 32 节）：写 agent_executions，重启不丢。 */
public class JpaAgentExecutionStore implements AgentExecutionStore {

  private final AgentExecutionRepository repository;

  public JpaAgentExecutionStore(AgentExecutionRepository repository) {
    this.repository = repository;
  }

  @Override
  public long start(String agentName, String source, Instant startedAt) {
    AgentExecutionEntity e = new AgentExecutionEntity();
    e.setAgentName(agentName);
    e.setSource(source);
    e.setStartedAt(startedAt);
    return repository.save(e).getId();
  }

  @Override
  public void finish(
      long id, String sessionId, boolean success, String errorMessage, Instant endedAt) {
    AgentExecutionEntity e = repository.findById(id).orElse(null);
    if (e == null) {
      return; // 记录不在（极少：库被清）——静默跳过，不因回填失败中断
    }
    e.setSessionId(sessionId);
    e.setSuccess(success);
    e.setErrorMessage(errorMessage);
    e.setEndedAt(endedAt);
    e.setDurationMs(Duration.between(e.getStartedAt(), endedAt).toMillis());
    repository.save(e);
  }

  @Override
  public List<AgentExecution> listByAgent(String agentName, int limit) {
    return repository
        .findByAgentNameOrderByStartedAtDescIdDesc(agentName, PageRequest.of(0, limit))
        .stream()
        .map(JpaAgentExecutionStore::toView)
        .toList();
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
        e.getErrorMessage());
  }
}
