package io.oryxos.core.auth;

/** Security event types emitted by management-console authentication. */
public enum AdminAuthEventType {
  LOGIN_SUCCEEDED,
  LOGIN_FAILED,
  LOGIN_LOCKED,
  LOGOUT
}
