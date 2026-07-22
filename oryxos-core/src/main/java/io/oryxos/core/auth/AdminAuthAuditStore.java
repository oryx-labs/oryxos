package io.oryxos.core.auth;

import java.util.List;

/** Persistence boundary for administrator authentication audit events. */
public interface AdminAuthAuditStore {

  void record(AdminAuthEvent event);

  List<AdminAuthEvent> findRecent(int limit);
}
