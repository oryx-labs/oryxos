package io.oryxos.channel.weixinkf;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/** 微信客服回复窗：用户主动发信后 48h / 每回合最多 5 条企业下行。 */
final class WeixinKfReplySessionStore {

  static final Duration SESSION_WINDOW = Duration.ofHours(48);
  static final int MAX_REPLIES_PER_WINDOW = 5;

  record Session(long expiresAtMs, int replyCount) {}

  private final ConcurrentHashMap<String, Session> byChatId = new ConcurrentHashMap<>();

  void rememberInbound(String chatId, long nowMs) {
    if (chatId == null || chatId.isBlank()) {
      return;
    }
    byChatId.put(chatId, new Session(nowMs + SESSION_WINDOW.toMillis(), 0));
  }

  Session requireForReply(String chatId, long nowMs) {
    Session session = byChatId.get(chatId);
    if (session == null) {
      throw new IllegalStateException("微信客服会话上下文缺失，需用户先发消息（chat=" + chatId + "）");
    }
    if (nowMs > session.expiresAtMs()) {
      byChatId.remove(chatId, session);
      throw new IllegalStateException("微信客服 48 小时回复窗已关闭，需用户再发一条消息（chat=" + chatId + "）");
    }
    if (session.replyCount() >= MAX_REPLIES_PER_WINDOW) {
      throw new IllegalStateException(
          "微信客服回复条数已达上限（" + MAX_REPLIES_PER_WINDOW + "），需用户再发一条消息（chat=" + chatId + "）");
    }
    return session;
  }

  void markReplied(String chatId, Session prior) {
    byChatId.computeIfPresent(
        chatId,
        (k, cur) -> {
          if (cur != prior && cur.expiresAtMs() != prior.expiresAtMs()) {
            return cur;
          }
          return new Session(cur.expiresAtMs(), cur.replyCount() + 1);
        });
  }
}
