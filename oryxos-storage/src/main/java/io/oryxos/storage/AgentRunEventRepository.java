package io.oryxos.storage;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** agent_run_events 仓库。 */
public interface AgentRunEventRepository extends JpaRepository<AgentRunEventEntity, Long> {

  List<AgentRunEventEntity> findByRunIdAndSequenceGreaterThanOrderBySequenceAsc(
      Long runId, Long sequence, Pageable pageable);

  Optional<AgentRunEventEntity> findTopByRunIdOrderBySequenceDesc(Long runId);

  @Query("select max(e.sequence) from AgentRunEventEntity e where e.runId = :runId")
  Long maxSequence(@Param("runId") Long runId);
}
