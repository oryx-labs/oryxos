package io.oryxos.web.controller.dto;

import io.oryxos.core.session.SessionSummary;
import java.time.Instant;

/** GET /sessions 列表视图：会话摘要，不含对话正文（避免列表过大）。 */
public record SessionSummaryView(
    String sessionId,
    String profileName,
    String channel,
    String userId,
    String status,
    Instant createdAt,
    Instant lastActiveAt,
    int messageCount) {

  public static SessionSummaryView from(SessionSummary s) {
    return new SessionSummaryView(
        s.sessionId(),
        s.profileName(),
        s.channel(),
        s.userId(),
        s.status(),
        s.createdAt(),
        s.lastActiveAt(),
        s.messageCount());
  }
}
