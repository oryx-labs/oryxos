package io.oryxos.storage;

import org.springframework.data.jpa.repository.JpaRepository;

/** auth_events 追加型仓储；业务层禁止 update/delete。 */
public interface AuthEventRepository extends JpaRepository<AuthEvent, Long> {}
