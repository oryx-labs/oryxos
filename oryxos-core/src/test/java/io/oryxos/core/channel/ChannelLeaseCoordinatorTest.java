package io.oryxos.core.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.core.cluster.ClusterProperties;
import io.oryxos.core.cluster.CoordinationStore;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class ChannelLeaseCoordinatorTest {

  /** 不排周期任务：本类的用例只关心第一次 tick 与 unmanage 的并发。 */
  private static ThreadPoolTaskScheduler idleScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setRemoveOnCancelPolicy(true);
    scheduler.initialize();
    return scheduler;
  }

  private static CoordinationStore grantingStore() {
    CoordinationStore store = mock(CoordinationStore.class);
    when(store.tryAcquireChannel(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    when(store.renewChannel(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    return store;
  }

  @Test
  @DisplayName("建连期间被 unmanage：那次连接必须回滚，不留无人管的连接")
  void unmanageDuringStartRollsBackTheConnection() throws Exception {
    ChannelLeaseCoordinator coordinator =
        new ChannelLeaseCoordinator(grantingStore(), new ClusterProperties(), idleScheduler());

    CountDownLatch startEntered = new CountDownLatch(1);
    CountDownLatch letStartReturn = new CountDownLatch(1);
    CountDownLatch startReturned = new CountDownLatch(1);
    AtomicInteger stops = new AtomicInteger();
    AtomicBoolean connected = new AtomicBoolean(false);

    Runnable startConnection =
        () -> {
          startEntered.countDown();
          awaitQuietly(letStartReturn);
          connected.set(true);
          startReturned.countDown();
        };
    Runnable stopConnection =
        () -> {
          stops.incrementAndGet();
          connected.set(false);
        };

    Thread manageThread =
        new Thread(
            () -> coordinator.manage("wecom", startConnection, stopConnection, connected::get),
            "manage");
    manageThread.start();
    assertThat(startEntered.await(2, TimeUnit.SECONDS)).isTrue();

    // 建连还阻塞在里面，渠道此刻被处置（删除/更新都会走到 unmanage）。
    coordinator.unmanage("wecom");
    letStartReturn.countDown();

    assertThat(startReturned.await(2, TimeUnit.SECONDS)).isTrue();
    manageThread.join(2000);

    assertThat(stops.get()).as("被处置之后跑完的那次建连必须被回滚，否则会留下一条不属于任何循环的连接").isEqualTo(1);
    assertThat(connected.get()).as("回滚之后连接状态应为未连接").isFalse();
  }

  @Test
  @DisplayName("建连期间被同名的下一次 manage 取代：旧的那次同样回滚")
  void supersededByANewerManageRollsBack() throws Exception {
    ChannelLeaseCoordinator coordinator =
        new ChannelLeaseCoordinator(grantingStore(), new ClusterProperties(), idleScheduler());

    CountDownLatch startEntered = new CountDownLatch(1);
    CountDownLatch letStartReturn = new CountDownLatch(1);
    AtomicInteger oldStops = new AtomicInteger();
    AtomicBoolean oldConnected = new AtomicBoolean(false);

    Thread manageThread =
        new Thread(
            () ->
                coordinator.manage(
                    "wecom",
                    () -> {
                      startEntered.countDown();
                      awaitQuietly(letStartReturn);
                      oldConnected.set(true);
                    },
                    () -> {
                      oldStops.incrementAndGet();
                      oldConnected.set(false);
                    },
                    oldConnected::get),
            "manage-old");
    manageThread.start();
    assertThat(startEntered.await(2, TimeUnit.SECONDS)).isTrue();

    // update：先 unmanage 旧的，再 manage 新的（ChannelAdminService 的顺序）。
    coordinator.unmanage("wecom");
    AtomicBoolean newConnected = new AtomicBoolean(false);
    coordinator.manage(
        "wecom", () -> newConnected.set(true), () -> newConnected.set(false), newConnected::get);
    letStartReturn.countDown();

    manageThread.join(2000);

    assertThat(oldStops.get()).as("被新的一代取代后，旧连接应当被回滚").isEqualTo(1);
    assertThat(newConnected.get()).as("新一代自己连上了，不应被旧一代的回滚波及").isTrue();
  }

  @Test
  @DisplayName("正常路径：没有被处置时，建连之后不发生回滚")
  void normalPathDoesNotRollBack() {
    ChannelLeaseCoordinator coordinator =
        new ChannelLeaseCoordinator(grantingStore(), new ClusterProperties(), idleScheduler());
    AtomicInteger stops = new AtomicInteger();
    AtomicBoolean connected = new AtomicBoolean(false);

    coordinator.manage(
        "wecom", () -> connected.set(true), () -> stops.incrementAndGet(), connected::get);

    assertThat(connected.get()).isTrue();
    assertThat(stops.get()).as("没有任何处置动作时不该调用 stop").isZero();
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
