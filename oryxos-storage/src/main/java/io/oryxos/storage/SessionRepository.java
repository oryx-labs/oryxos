package io.oryxos.storage;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** sessions 的读写通道；按 profile 查询供 session list 与后续 Web 端点使用。 */
public interface SessionRepository extends JpaRepository<Session, String> {

  List<Session> findByProfileName(String profileName);

  long countByStatus(String status);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Transactional(rollbackFor = Exception.class)
  @Query(
      """
      UPDATE Session s
         SET s.messagesJson = :messagesJson, s.lastActiveAt = :lastActiveAt
       WHERE s.sessionId = :sessionId
         AND (s.messagesJson = :expectedMessagesJson
              OR (s.messagesJson IS NULL AND :expectedMessagesJson = '[]'))
      """)
  int updateMessagesIfUnchanged(
      @Param("sessionId") String sessionId,
      @Param("expectedMessagesJson") String expectedMessagesJson,
      @Param("messagesJson") String messagesJson,
      @Param("lastActiveAt") Instant lastActiveAt);
}
