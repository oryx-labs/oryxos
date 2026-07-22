package io.oryxos.storage;

import io.oryxos.core.auth.AdminAuthAuditStore;
import io.oryxos.core.auth.AdminAuthEvent;
import io.oryxos.core.auth.AdminAuthEventType;
import java.util.List;
import org.springframework.data.domain.PageRequest;

/** JPA-backed implementation of administrator authentication audit persistence. */
public class JpaAdminAuthAuditStore implements AdminAuthAuditStore {

  private static final int MAX_LIMIT = 100;

  private final AdminAuthEventRepository repository;

  public JpaAdminAuthAuditStore(AdminAuthEventRepository repository) {
    this.repository = repository;
  }

  @Override
  public void record(AdminAuthEvent event) {
    AdminAuthEventEntity entity = new AdminAuthEventEntity();
    entity.setPrincipal(clean(event.principal(), 255));
    entity.setEventType(event.eventType().name());
    entity.setRemoteAddress(clean(event.remoteAddress(), 128));
    entity.setUserAgent(clean(event.userAgent(), 512));
    entity.setSessionId(clean(event.sessionId(), 255));
    entity.setCreatedAt(event.occurredAt());
    repository.save(entity);
  }

  @Override
  public List<AdminAuthEvent> findRecent(int limit) {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new IllegalArgumentException("limit must be between 1 and 100");
    }
    return repository.findByOrderByCreatedAtDesc(PageRequest.of(0, limit)).stream()
        .map(this::toEvent)
        .toList();
  }

  private AdminAuthEvent toEvent(AdminAuthEventEntity entity) {
    return new AdminAuthEvent(
        entity.getPrincipal(),
        AdminAuthEventType.valueOf(entity.getEventType()),
        entity.getCreatedAt(),
        entity.getRemoteAddress(),
        entity.getUserAgent(),
        entity.getSessionId());
  }

  private static String clean(String value, int maxLength) {
    if (value == null) {
      return null;
    }
    String sanitized = value.replace('\r', '_').replace('\n', '_');
    return sanitized.length() <= maxLength ? sanitized : sanitized.substring(0, maxLength);
  }
}
