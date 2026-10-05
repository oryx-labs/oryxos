package io.oryxos.core.channel;

import io.oryxos.core.cluster.CoordinationStore;

/**
 * 跨副本去重（026 集群档）：两级判重——进程内一级缓存（同副本重复占多数，热路径免 DB）+ channel_event_receipts 回执表 INSERT
 * 冲突判重（跨副本硬闸）。无论首见还是撞冲突都记入本地 缓存，避免同一重复键反复查库。
 */
public class SharedReceiptDeduplicator implements MessageDeduplicator {

  private final InMemoryMessageDeduplicator localCache = new InMemoryMessageDeduplicator();
  private final CoordinationStore store;

  private volatile io.oryxos.core.metrics.MetricsRecorder metrics =
      io.oryxos.core.metrics.MetricsRecorder.NOOP;

  public void setMetricsRecorder(io.oryxos.core.metrics.MetricsRecorder metrics) {
    this.metrics = metrics;
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "注入的 CoordinationStore 是 Spring 共享 Bean，本就不应防御性拷贝。")
  public SharedReceiptDeduplicator(CoordinationStore store) {
    this.store = store;
  }

  @Override
  public boolean markIfFirst(String key) {
    if (!localCache.markIfFirst(key)) {
      metrics.recordDuplicateDropped(channelOf(key));
      return false; // 本副本已见：热路径零 DB 访问
    }
    // 本地首见 → 共享回执硬闸（另一副本可能已处理过：平台超时重推跨副本）
    boolean first = store.markReceipt(key);
    if (!first) {
      metrics.recordDuplicateDropped(channelOf(key));
    }
    return first;
  }

  @Override
  public void release(String key) {
    // 两级都要撤：只撤共享库的话，本地缓存仍认为「已见」，同一 key 再也进不来。
    localCache.release(key);
    store.unmarkReceipt(key);
  }

  private static String channelOf(String key) {
    int colon = key.indexOf(':');
    return colon > 0 ? key.substring(0, colon) : "unknown";
  }
}
