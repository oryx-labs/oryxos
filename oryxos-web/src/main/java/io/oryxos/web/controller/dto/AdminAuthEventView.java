package io.oryxos.web.controller.dto;

import java.time.Instant;

/** Sanitized administrator authentication audit event returned to authenticated admins. */
public record AdminAuthEventView(
    String principal,
    String eventType,
    Instant occurredAt,
    String remoteAddress,
    String userAgent) {}
