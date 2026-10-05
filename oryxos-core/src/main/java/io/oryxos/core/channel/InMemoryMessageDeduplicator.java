package io.oryxos.core.channel;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 进程内去重实现（017 R3，026 起为单机档默认）：LRU 容量 + TTL 双重界限防内存无界。单实例语义 达标；多副本档由
 * SharedReceiptDeduplicator（回执落共享库）接管跨副本判重。
 */
public class InMemoryMessageDeduplicator implements MessageDeduplicator {

  private static final int DEFAULT_CAPACITY = 5000;
  private static final Duration DEFAULT_TTL = Duration.ofHours(12);

  private final int capacity;
  private final Duration ttl;
  private final Clock clock;
  private final LinkedHashMap<String, Instant> seen;

  public InMemoryMessageDeduplicator() {
    this(DEFAULT_CAPACITY, DEFAULT_TTL, Clock.systemUTC());
  }

  public InMemoryMessageDeduplicator(int capacity, Duration ttl, Clock clock) {
    this.capacity = capacity;
    this.ttl = ttl;
    this.clock = clock;
    // accessOrder=false：按插入序淘汰最老条目即可，去重键不存在"热点续期"语义
    this.seen =
        new LinkedHashMap<>(16, 0.75f, false) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<String, Instant> eldest) {
            return size() > InMemoryMessageDeduplicator.this.capacity;
          }
        };
  }

  @Override
  public synchronized boolean markIfFirst(String key) {
    Instant now = clock.instant();
    evictExpired(now);
    Instant existing = seen.get(key);
    if (existing != null) {
      return false;
    }
    seen.put(key, now);
    return true;
  }

  @Override
  public synchronized void release(String key) {
    seen.remove(key);
  }

  /** 从最老条目起清掉已过 TTL 的登记（插入序即时间序，遇到未过期即可停）。 */
  private void evictExpired(Instant now) {
    Instant cutoff = now.minus(ttl);
    Iterator<Map.Entry<String, Instant>> it = seen.entrySet().iterator();
    while (it.hasNext()) {
      if (it.next().getValue().isBefore(cutoff)) {
        it.remove();
      } else {
        break;
      }
    }
  }
}
