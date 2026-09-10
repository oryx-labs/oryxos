package io.oryxos.channel.douyin;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/** 私信回复上下文：conversation_id + msg_id + 24h 窗与条数。 */
final class DouyinReplySessionStore {

  static final Duration SESSION_WINDOW = Duration.ofHours(24);
  static final int MAX_REPLIES_PER_WINDOW = 6;

  record Session(String conversationId, String lastMsgId, long expiresAtMs, int replyCount) {}

  private final ConcurrentHashMap<String, Session> byUserOpenId = new ConcurrentHashMap<>();

  void rememberInbound(String userOpenId, String conversationId, String msgId, long nowMs) {
    if (userOpenId == null || userOpenId.isBlank()) {
      return;
    }
    byUserOpenId.put(
        userOpenId, new Session(conversationId, msgId, nowMs + SESSION_WINDOW.toMillis(), 0));
  }

  Session requireForReply(String userOpenId, long nowMs) {
    Session session = byUserOpenId.get(userOpenId);
    if (session == null) {
      throw new IllegalStateException("抖音私信会话上下文缺失，需用户先发私信（user=" + userOpenId + "）");
    }
    if (nowMs > session.expiresAtMs()) {
      byUserOpenId.remove(userOpenId, session);
      throw new IllegalStateException("抖音私信 24 小时回复窗已关闭，需用户再发一条私信（user=" + userOpenId + "）");
    }
    if (session.replyCount() >= MAX_REPLIES_PER_WINDOW) {
      throw new IllegalStateException(
          "抖音私信回复条数已达上限（" + MAX_REPLIES_PER_WINDOW + "），需用户再发一条私信（user=" + userOpenId + "）");
    }
    if (session.conversationId() == null
        || session.conversationId().isBlank()
        || session.lastMsgId() == null
        || session.lastMsgId().isBlank()) {
      throw new IllegalStateException("抖音私信会话缺少 conversation_id/msg_id（user=" + userOpenId + "）");
    }
    return session;
  }

  void markReplied(String userOpenId, Session prior) {
    byUserOpenId.computeIfPresent(
        userOpenId,
        (k, cur) -> {
          if (cur != prior && !sameKey(cur, prior)) {
            return cur;
          }
          return new Session(
              cur.conversationId(), cur.lastMsgId(), cur.expiresAtMs(), cur.replyCount() + 1);
        });
  }

  private static boolean sameKey(Session a, Session b) {
    return a.conversationId().equals(b.conversationId()) && a.lastMsgId().equals(b.lastMsgId());
  }
}
