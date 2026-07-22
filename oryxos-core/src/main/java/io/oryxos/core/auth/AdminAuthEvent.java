package io.oryxos.core.auth;

import java.time.Instant;
import java.util.Objects;

/** Immutable security audit event for administrator authentication. */
public final class AdminAuthEvent {

  private final String principal;
  private final AdminAuthEventType eventType;
  private final Instant occurredAt;
  private final String remoteAddress;
  private final String userAgent;
  private final String sessionId;

  public AdminAuthEvent(
      String principal,
      AdminAuthEventType eventType,
      Instant occurredAt,
      String remoteAddress,
      String userAgent,
      String sessionId) {
    this.principal = requireText(principal, "principal");
    this.eventType = Objects.requireNonNull(eventType, "eventType");
    this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    this.remoteAddress = remoteAddress;
    this.userAgent = userAgent;
    this.sessionId = sessionId;
  }

  public String principal() {
    return principal;
  }

  public AdminAuthEventType eventType() {
    return eventType;
  }

  public Instant occurredAt() {
    return occurredAt;
  }

  public String remoteAddress() {
    return remoteAddress;
  }

  public String userAgent() {
    return userAgent;
  }

  public String sessionId() {
    return sessionId;
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }
}
