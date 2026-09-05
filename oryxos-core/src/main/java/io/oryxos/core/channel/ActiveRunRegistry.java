package io.oryxos.core.channel;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 入站进行中推理登记：chatKey → sessionId，供群聊/私聊 {@code /stop} 定位可中断的临时或持久会话。
 *
 * <p>策略：同 chat last-wins；{@link #unregister} 仅当仍是该 sessionId 时删除，避免旧任务 finally 清掉新任务。
 */
public final class ActiveRunRegistry {

  private final ConcurrentMap<String, String> byChat = new ConcurrentHashMap<>();

  public void register(String chatKey, String sessionId) {
    if (chatKey == null || sessionId == null) {
      return;
    }
    byChat.put(chatKey, sessionId);
  }

  /** 仅当当前登记仍是 {@code sessionId} 时清除。 */
  public void unregister(String chatKey, String sessionId) {
    if (chatKey == null || sessionId == null) {
      return;
    }
    byChat.computeIfPresent(chatKey, (k, current) -> sessionId.equals(current) ? null : current);
  }

  public Optional<String> current(String chatKey) {
    if (chatKey == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(byChat.get(chatKey));
  }

  static String chatKey(String channelType, String chatId) {
    return channelType + ":" + chatId;
  }
}
