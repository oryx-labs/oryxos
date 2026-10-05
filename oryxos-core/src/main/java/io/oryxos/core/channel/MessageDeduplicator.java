package io.oryxos.core.channel;

/**
 * 入站事件去重契约（017 R3 / 026 接口化）：按 {@code channelName:messageId} 判重，重复到达静默 丢弃保证恰好一答。单机档 {@link
 * InMemoryMessageDeduplicator}；多副本档 SharedReceiptDeduplicator （回执落共享库，跨副本生效——飞书超时重推落到另一副本的场景由此收口）。
 */
public interface MessageDeduplicator {

  /**
   * 原子判重：首次出现返回 true 并登记；重复返回 false。
   *
   * @param key 去重键（约定 {@code channelName + ":" + messageId}）
   */
  boolean markIfFirst(String key);

  /**
   * 撤销一次占用，与 {@link #markIfFirst} 对称。
   *
   * <p>用于「已占用、但最终没能给出任何答复」的失败路径：不撤销的话，平台对同一 {@code key} 的重推会被判为重复而静默丢弃，用户拿到 0 条回答。撤销之后同一 key 再次
   * {@link #markIfFirst} 必须重新返回 true。
   */
  void release(String key);
}
