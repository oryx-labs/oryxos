package io.oryxos.channel.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.InboundMessageService;
import io.oryxos.core.channel.OutboundGuard;
import io.oryxos.core.profile.ProfileRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Gateway 重连时机的选择。 */
class DiscordChannelAdapterReconnectTest {

  private DiscordChannelAdapter adapter;

  @BeforeEach
  void setUp() {
    ChannelConfig config =
        new ChannelConfig("discord-test", "discord", "xoxb-test", "xapp-test", "demo-agent", true);
    adapter =
        new DiscordChannelAdapter(
            config,
            mock(ProfileRegistry.class),
            mock(InboundMessageService.class),
            mock(OutboundGuard.class));
  }

  @Test
  @DisplayName("服务端轮换只免除紧随其后的第一次重连，之后回到 attempt 退避")
  void gracefulRotationSkipsOnlyTheFirstBackoff() {
    adapter.noteDisconnected(DiscordDisconnectKind.GRACEFUL);

    assertEquals(0L, adapter.nextReconnectDelayMs(), "轮换后的第一次重连应立即进行");

    // 重连失败会再次走到这里。轮换标记若不被消费，这里读到的仍是 GRACEFUL ——
    // delayMs 恒为 0，退避形同虚设，对端持续不可用时会变成无间隔的重试热循环。
    assertTrue(adapter.nextReconnectDelayMs() > 0, "第一次重连之后必须回到 attempt 退避，不能继续免除");
  }

  @Test
  @DisplayName("非轮换断开从一开始就按 attempt 退避")
  void abruptDisconnectBacksOffImmediately() {
    adapter.noteDisconnected(DiscordDisconnectKind.ABRUPT);
    assertTrue(adapter.nextReconnectDelayMs() > 0);
  }

  @Test
  @DisplayName("轮换之后的重连仍随失败次数增长，直到上限")
  void backoffKeepsGrowingAfterRotation() {
    adapter.noteDisconnected(DiscordDisconnectKind.GRACEFUL);
    adapter.nextReconnectDelayMs();
    long first = adapter.nextReconnectDelayMs();
    assertTrue(first >= DiscordChannelAdapter.reconnectDelayMs(0));
    assertTrue(DiscordChannelAdapter.reconnectDelayMs(99) <= 60_000L);
  }
}
