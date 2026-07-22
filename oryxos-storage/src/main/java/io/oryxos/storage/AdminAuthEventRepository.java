package io.oryxos.storage;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for administrator authentication audit events. */
public interface AdminAuthEventRepository extends JpaRepository<AdminAuthEventEntity, Long> {

  List<AdminAuthEventEntity> findByOrderByCreatedAtDesc(Pageable pageable);
}
