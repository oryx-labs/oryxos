package io.oryxos.storage;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** agent_executions 仓库（第 32 节）。 */
public interface AgentExecutionRepository extends JpaRepository<AgentExecutionEntity, Long> {

  List<AgentExecutionEntity> findByAgentNameOrderByStartedAtDescIdDesc(
      String agentName, Pageable pageable);
}
