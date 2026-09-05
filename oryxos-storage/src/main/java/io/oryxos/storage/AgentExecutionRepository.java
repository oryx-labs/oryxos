package io.oryxos.storage;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** agent_executions 仓库（第 32 节）。 */
public interface AgentExecutionRepository extends JpaRepository<AgentExecutionEntity, Long> {

  List<AgentExecutionEntity> findByAgentNameOrderByStartedAtDescIdDesc(
      String agentName, Pageable pageable);

  List<AgentExecutionEntity> findAllByOrderByStartedAtDescIdDesc(Pageable pageable);

  List<AgentExecutionEntity> findByStatusOrderByStartedAtDescIdDesc(
      String status, Pageable pageable);

  List<AgentExecutionEntity> findByStatusIn(java.util.Collection<String> statuses);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Transactional(rollbackFor = Exception.class)
  @Query(
      """
      update AgentExecutionEntity e
         set e.status = 'RUNNING', e.updatedAt = :updatedAt
       where e.id = :id and e.endedAt is null
      """)
  int markRunningIfOpen(@Param("id") long id, @Param("updatedAt") java.time.Instant updatedAt);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Transactional(rollbackFor = Exception.class)
  @Query(
      """
      update AgentExecutionEntity e
         set e.cancelRequestedAt = :requestedAt,
             e.status = 'CANCELLING',
             e.updatedAt = :requestedAt
       where e.id = :id and e.endedAt is null
      """)
  int requestCancelIfOpen(
      @Param("id") long id, @Param("requestedAt") java.time.Instant requestedAt);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Transactional(rollbackFor = Exception.class)
  @Query(
      """
      update AgentExecutionEntity e
         set e.sessionId = :sessionId,
             e.success = :success,
             e.errorMessage = :errorMessage,
             e.endedAt = :endedAt,
             e.durationMs = :durationMs,
             e.updatedAt = :endedAt,
             e.status = :status,
             e.stopReason = :stopReason
       where e.id = :id and e.endedAt is null
      """)
  int finishIfOpen(
      @Param("id") long id,
      @Param("sessionId") String sessionId,
      @Param("success") boolean success,
      @Param("errorMessage") String errorMessage,
      @Param("endedAt") java.time.Instant endedAt,
      @Param("durationMs") long durationMs,
      @Param("status") String status,
      @Param("stopReason") String stopReason);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Transactional(rollbackFor = Exception.class)
  @Query("update AgentExecutionEntity e set e.updatedAt = :updatedAt where e.id = :id")
  int touchUpdatedAt(@Param("id") long id, @Param("updatedAt") java.time.Instant updatedAt);
}
