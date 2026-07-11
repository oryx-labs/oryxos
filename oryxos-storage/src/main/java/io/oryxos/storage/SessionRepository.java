package io.oryxos.storage;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** sessions 的读写通道；按 profile 查询供 session list 与后续 Web 端点使用。 */
public interface SessionRepository extends JpaRepository<Session, String> {

  List<Session> findByProfileName(String profileName);
}
