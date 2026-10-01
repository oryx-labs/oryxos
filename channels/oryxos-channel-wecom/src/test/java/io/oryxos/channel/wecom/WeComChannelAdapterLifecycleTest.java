package io.oryxos.channel.wecom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.InboundMessageService;
import io.oryxos.core.channel.OutboundGuard;
import io.oryxos.core.profile.ProfileRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WeComChannelAdapterLifecycleTest {

  @Test
  @DisplayName("重连退避：指数增长并封顶")
  void reconnectDelayUsesExponentialBackoffWithCap() {
    assertEquals(2_000L, WeComChannelAdapter.reconnectDelayMs(0));
    assertEquals(4_000L, WeComChannelAdapter.reconnectDelayMs(1));
    assertEquals(8_000L, WeComChannelAdapter.reconnectDelayMs(2));
    assertEquals(60_000L, WeComChannelAdapter.reconnectDelayMs(10));
  }

  @Test
  @DisplayName("重连退避：负 attempt 按 0 处理")
  void reconnectDelayClampsNegativeAttempt() {
    assertTrue(WeComChannelAdapter.reconnectDelayMs(-1) >= 2_000L);
  }

  @Test
  @DisplayName("重连路径复用 MessageSender，chatTypes 映射不丢失")
  void outboundStackReusedAcrossReconnectInit() throws Exception {
    ChannelConfig config =
        new ChannelConfig("ops-wecom", "wecom", "bot", "secret", "demo-agent", true);
    WeComChannelAdapter adapter =
        new WeComChannelAdapter(
            config,
            new ProfileRegistry(),
            mock(InboundMessageService.class),
            (OutboundGuard) url -> {});

    Method ensure = WeComChannelAdapter.class.getDeclaredMethod("ensureOutboundStack");
    ensure.setAccessible(true);
    Field senderField = WeComChannelAdapter.class.getDeclaredField("sender");
    senderField.setAccessible(true);

    ensure.invoke(adapter);
    WeComMessageSender first = (WeComMessageSender) senderField.get(adapter);
    first.rememberChatType("chat-1", 1);

    ensure.invoke(adapter);
    WeComMessageSender second = (WeComMessageSender) senderField.get(adapter);
    assertSame(first, second);
  }
}
