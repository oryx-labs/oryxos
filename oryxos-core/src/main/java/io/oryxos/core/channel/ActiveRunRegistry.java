package io.oryxos.core.channel;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 入站进行中推理登记：chatKey → 当前运行，供群聊/私聊 {@code /stop} 定位可中断的临时或持久会话。
 *
 * <p>策略：同 chat last-wins；{@link #unregister} 仅当当前登记仍是<b>这一次运行</b>时删除， 避免旧任务 finally 清掉新任务。
 *
 * <p>★ 撤销的身份必须是「哪一次运行」，不能是 sessionId：私聊按「渠道 + 用户 + Agent」维持连续会话， 同一用户先后两次运行的 sessionId 完全相同，用
 * sessionId 做比较时先结束的那次会把后一次（仍在跑） 的登记一并删掉 —— 那正是本类要避免的情形，而且 {@code /stop} 会因此回「没有正在执行的任务」。
 */
public final class ActiveRunRegistry {

  /** register 入参非法时返回的令牌，unregister 会忽略它。 */
  static final long NO_TOKEN = 0L;

  private record Run(long token, String sessionId) {}

  private final ConcurrentMap<String, Run> byChat = new ConcurrentHashMap<>();
  private final AtomicLong sequence = new AtomicLong();

  /**
   * 登记一次运行（同 chat last-wins）。
   *
   * @return 本次运行的令牌，撤销时必须原样交回
   */
  public long register(String chatKey, String sessionId) {
    if (chatKey == null || sessionId == null) {
      return NO_TOKEN;
    }
    long token = sequence.incrementAndGet();
    byChat.put(chatKey, new Run(token, sessionId));
    return token;
  }

  /** 仅当当前登记仍是这一次运行（令牌相同）时清除。 */
  public void unregister(String chatKey, long token) {
    if (chatKey == null || token == NO_TOKEN) {
      return;
    }
    byChat.computeIfPresent(chatKey, (k, current) -> current.token() == token ? null : current);
  }

  /** 当前登记的会话，供 {@code /stop} 定位要中断的对象。 */
  public Optional<String> current(String chatKey) {
    if (chatKey == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(byChat.get(chatKey)).map(Run::sessionId);
  }

  static String chatKey(String channelType, String chatId) {
    return channelType + ":" + chatId;
  }
}
