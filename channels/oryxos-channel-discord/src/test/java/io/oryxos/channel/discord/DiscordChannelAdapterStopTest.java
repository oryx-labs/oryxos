package io.oryxos.channel.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.ChannelStatus;
import io.oryxos.core.channel.InboundMessageService;
import io.oryxos.core.channel.OutboundGuard;
import io.oryxos.core.profile.ProfileRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DiscordChannelAdapterStopTest {

  private DiscordChannelAdapter adapter;

  @BeforeEach
  void setUp() {
    ChannelConfig config =
        new ChannelConfig(
            "discord-test", "discord", "bot-token-test", "app-id-test", "demo-agent", true);
    adapter =
        new DiscordChannelAdapter(
            config,
            mock(ProfileRegistry.class),
            mock(InboundMessageService.class),
            mock(OutboundGuard.class));
  }

  @Test
  @DisplayName("未启动时 stop 安全")
  void stopWithoutStartIsSafe() {
    adapter.stop();
    assertEquals(ChannelStatus.State.DISCONNECTED, adapter.status().state());
  }

  @Test
  @DisplayName("重连退避随 attempt 增长且有上限")
  void reconnectBackoffGrows() {
    long d0 = DiscordChannelAdapter.reconnectDelayMs(0);
    long d3 = DiscordChannelAdapter.reconnectDelayMs(3);
    long d99 = DiscordChannelAdapter.reconnectDelayMs(99);
    assertTrue(d0 >= 2_000L);
    assertTrue(d3 > d0);
    assertTrue(d99 <= 60_000L);
  }
}
