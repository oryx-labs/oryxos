package io.oryxos.core.session;

import java.time.Instant;

/**
 * 会话摘要投影：供"列出会话"用，不含对话正文（messages_json）以免列表过大。
 *
 * <p>与领域对象 {@link Session}（只有 sessionId/profileName/messages）互补——摘要字段（channel/user/status/时间戳/条数）
 * 来自持久化实体 {@code io.oryxos.storage.Session}，由 {@code SessionManager} 的实现投影而来。
 */
public record SessionSummary(
    String sessionId,
    String profileName,
    String channel,
    String userId,
    String status,
    Instant createdAt,
    Instant lastActiveAt,
    int messageCount) {}
