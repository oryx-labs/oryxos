package io.oryxos.core.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 登记表的唯一视图：并发登记/离线时，同一渠道名在任何一次快照里都只能出现一行。 */
class InboundChannelRegistryTest {

  private static ChannelStatus offlineStatus(String name) {
    return new ChannelStatus(name, "stub", "agent", ChannelStatus.State.STANDBY, null);
  }

  @Test
  @DisplayName("并发「登记在线」与「登记离线」：同一次 statusAll 快照里不得出现同名两行")
  void concurrentRegisterAndOfflineNeverYieldDuplicateRows() throws Exception {
    InboundChannelRegistry registry = new InboundChannelRegistry();
    int iterations = 2000;
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(3);
    AtomicBoolean duplicated = new AtomicBoolean(false);

    // 两个写者：一个反复登记在线（协调器 tick 线程），一个反复登记离线（管理线程）
    Thread online =
        new Thread(
            () -> {
              await(start);
              for (int i = 0; i < iterations; i++) {
                registry.register(new StubChannelAdapter("wecom", "agent"));
              }
              done.countDown();
            });
    Thread offline =
        new Thread(
            () -> {
              await(start);
              for (int i = 0; i < iterations; i++) {
                registry.registerOffline(offlineStatus("wecom"));
              }
              done.countDown();
            });
    // 一个读者：状态端点就是这样读的
    Thread reader =
        new Thread(
            () -> {
              await(start);
              for (int i = 0; i < iterations * 4; i++) {
                List<ChannelStatus> snapshot = registry.statusAll();
                long distinct = snapshot.stream().map(ChannelStatus::name).distinct().count();
                if (distinct != snapshot.size()) {
                  duplicated.set(true);
                  done.countDown(); // 先放行，让断言报出真正的原因而不是 latch 超时
                  return;
                }
              }
              done.countDown();
            });

    online.start();
    offline.start();
    reader.start();
    start.countDown();
    assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();

    assertThat(duplicated).as("同一次快照里同一渠道名出现两行，说明登记不是原子的：读者落在两次写之间").isFalse();
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
