package io.oryxos.storage;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** web_users 的访问通道；按 username 查用于 Basic Auth 校验。 */
public interface WebUserRepository extends JpaRepository<WebUser, Long> {

  Optional<WebUser> findByUsername(String username);

  boolean existsByUsername(String username);
}
