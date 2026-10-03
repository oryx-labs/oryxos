package io.oryxos.core.channel;

import io.oryxos.core.cluster.ClusterProperties;
import io.oryxos.core.cluster.CoordinationStore;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;

/**
 * 独连型渠道连接属主（026 R6，现阶段=企微单连接互踢语义）：持 channel_leases 租约的副本才建连，
 * 未持有者按心跳间隔持续竞争（属主死后其租约过期即被抢到、接管建连）；续租失败立即停连—— 两副本永不互踢。飞书/钉钉（集群随机投一型）不经此协调，各副本独立建连。单机档不装配。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "CRLF_INJECTION_LOGS",
    justification = "本类全部日志占位符仅填经 sanitize 的 channelName（lambda 内合成方法工具无法定位方法级豁免）。")
public class ChannelLeaseCoordinator {

  private static final Logger LOG = LoggerFactory.getLogger(ChannelLeaseCoordinator.class);

  private final CoordinationStore store;
  private final ClusterProperties properties;
  private final TaskScheduler scheduler;
  private final Map<String, ScheduledFuture<?>> loops = new ConcurrentHashMap<>();

  /** 每个渠道当前的世代号：manage 递增，unmanage 也递增（让在跑的那一代立刻过期）。 */
  private final Map<String, Long> generations = new ConcurrentHashMap<>();

  private volatile io.oryxos.core.metrics.MetricsRecorder metrics =
      io.oryxos.core.metrics.MetricsRecorder.NOOP;

  public void setMetricsRecorder(io.oryxos.core.metrics.MetricsRecorder metrics) {
    this.metrics = metrics;
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "注入的 store/scheduler/配置是 Spring 共享 Bean，本就不应防御性拷贝。")
  public ChannelLeaseCoordinator(
      CoordinationStore store, ClusterProperties properties, TaskScheduler scheduler) {
    this.store = store;
    this.properties = properties;
    this.scheduler = scheduler;
  }

  /**
   * 管理一条独连型渠道：立即尝试认领（成功即回调 start），并挂起竞争/续租循环。
   *
   * @param channelName 渠道名（channel_leases 主键）
   * @param startConnection 获得属主时建连（幂等：已连则 no-op 由适配器 start 语义保证）
   * @param stopConnection 失去属主时停连
   * @param isConnected 当前连接状态（避免重复 start/stop）
   */
  public void manage(
      String channelName,
      Runnable startConnection,
      Runnable stopConnection,
      Supplier<Boolean> isConnected) {
    Duration interval = properties.effectiveHeartbeatInterval();
    long generation = generations.merge(channelName, 1L, Long::sum);
    Runnable loop =
        () -> {
          try {
            tick(channelName, startConnection, stopConnection, isConnected, generation);
          } catch (RuntimeException e) {
            LOG.warn("渠道属主循环异常（下一周期重试）: channel={}", sanitize(channelName), e);
          }
        };
    loop.run(); // 启动期立即竞争一次
    loops.put(
        channelName,
        scheduler.scheduleAtFixedRate(loop, java.time.Instant.now().plus(interval), interval));
  }

  private void tick(
      String channelName,
      Runnable startConnection,
      Runnable stopConnection,
      Supplier<Boolean> isConnected,
      long generation) {
    if (!isCurrent(channelName, generation)) {
      // 这一代已被 unmanage 或被新的 manage 取代，不再产生任何副作用。
      return;
    }
    String owner = properties.owner();
    Duration ttl = properties.getLeaseTtl();
    boolean held =
        Boolean.TRUE.equals(isConnected.get())
            ? store.renewChannel(channelName, owner, ttl)
            : store.tryAcquireChannel(channelName, owner, ttl);
    if (held && !Boolean.TRUE.equals(isConnected.get())) {
      LOG.info("获得渠道连接属主，建立连接: channel={}", sanitize(channelName));
      metrics.recordLeaseAcquired("channel");
      boolean started;
      try {
        startConnection.run();
        started = true;
      } catch (RuntimeException e) {
        // 建连失败但租约已经拿到手。若就这么放着：下一轮 tryAcquire 会撞上自己那行，
        // 而它没过期 → takeExpired 返回 0 → 既不重试也不再续租，standby 同样接管不了，
        // 直到 TTL 到期；到期后谁抢到又是随机的，抢到者若再失败，接管时间就没有上界。
        // 释放租约让下一轮（或 standby）能立刻重来，重试才有可能收敛。
        started = false;
        LOG.warn("建连失败，释放属主以便下一轮重试: channel={}", sanitize(channelName), e);
        store.releaseChannel(channelName, owner);
        throw e;
      }
      if (started && !isCurrent(channelName, generation)) {
        // 建连可能长时间阻塞（企微最坏约 20s）。跑完再确认这一代还算不算数：
        // 不算数说明期间渠道已被处置，这次连接不该存在 —— 用同一个 stop 回调回滚。
        LOG.warn("建连期间渠道已被处置，回滚这次连接: channel={}", sanitize(channelName));
        stopConnection.run();
      }
    } else if (!held && Boolean.TRUE.equals(isConnected.get())) {
      LOG.warn("渠道连接属主已失去（fencing），停止连接: channel={}", sanitize(channelName));
      metrics.recordFenceConflict("channel");
      stopConnection.run();
    }
  }

  /** 这一代是否仍是该渠道的当前世代。 */
  private boolean isCurrent(String channelName, long generation) {
    return generations.getOrDefault(channelName, 0L) == generation;
  }

  /** 停止管理（渠道下线/删除时）：先让在跑的那一代失效，再撤循环并释放属主。 */
  public void unmanage(String channelName) {
    // 先换代：此刻可能有一次 startConnection 正阻塞在建连上，
    // 它返回时会发现自己那一代已经不算数，从而回滚这次连接。
    generations.merge(channelName, 1L, Long::sum);
    ScheduledFuture<?> loop = loops.remove(channelName);
    if (loop != null) {
      loop.cancel(false);
    }
    store.releaseChannel(channelName, properties.owner());
  }

  private static String sanitize(String value) {
    return value == null ? null : value.replace('\r', '_').replace('\n', '_');
  }
}
