package io.oryxos.storage;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** web_sessions 的访问通道；按 session_id 查用于 cookie 校验。 */
public interface WebSessionRepository extends JpaRepository<WebSession, Long> {

  Optional<WebSession> findBySessionId(String sessionId);

  void deleteBySessionId(String sessionId);
}
