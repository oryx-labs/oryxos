package io.oryxos.storage;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** auth_events 的访问通道。追加型审计：故意不提供 delete。 */
public interface AuthEventRepository extends JpaRepository<AuthEvent, Long> {

  List<AuthEvent> findByUsernameOrderByCreatedAtDesc(String username);

  List<AuthEvent> findByExternalSubjectOrderByCreatedAtDesc(String externalSubject);

  List<AuthEvent> findByEventTypeOrderByCreatedAtDesc(String eventType);

  List<AuthEvent> findAllByOrderByCreatedAtDesc();
}
