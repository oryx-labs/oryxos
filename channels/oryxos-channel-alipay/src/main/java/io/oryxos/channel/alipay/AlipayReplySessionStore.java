package io.oryxos.channel.alipay;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 支付宝生活号回复窗：用户主动交互后约 48h 可 {@code custom.send}。
 *
 * <p>公开文未钉每回合条数；MVP 只强制 48h，平台错误码 fail-loud。
 */
final class AlipayReplySessionStore {

  static final Duration SESSION_WINDOW = Duration.ofHours(48);

  record Session(long expiresAtMs) {}

  private final ConcurrentHashMap<String, Session> byChatId = new ConcurrentHashMap<>();

  void rememberInbound(String chatId, long nowMs) {
    if (chatId == null || chatId.isBlank()) {
      return;
    }
    byChatId.put(chatId, new Session(nowMs + SESSION_WINDOW.toMillis()));
  }

  Session requireForReply(String chatId, long nowMs) {
    Session session = byChatId.get(chatId);
    if (session == null) {
      throw new IllegalStateException("支付宝会话上下文缺失，需用户先发消息（chat=" + chatId + "）");
    }
    if (nowMs > session.expiresAtMs()) {
      byChatId.remove(chatId, session);
      throw new IllegalStateException("支付宝 48 小时回复窗已关闭，需用户再发一条消息（chat=" + chatId + "）");
    }
    return session;
  }
}
