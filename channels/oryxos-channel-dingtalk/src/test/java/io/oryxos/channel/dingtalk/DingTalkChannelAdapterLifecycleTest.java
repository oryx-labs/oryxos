package io.oryxos.channel.dingtalk;

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

class DingTalkChannelAdapterLifecycleTest {

  @Test
  @DisplayName("重连退避：指数增长并封顶")
  void reconnectDelayUsesExponentialBackoffWithCap() {
    assertEquals(2_000L, DingTalkChannelAdapter.reconnectDelayMs(0));
    assertEquals(4_000L, DingTalkChannelAdapter.reconnectDelayMs(1));
    assertEquals(8_000L, DingTalkChannelAdapter.reconnectDelayMs(2));
    assertEquals(60_000L, DingTalkChannelAdapter.reconnectDelayMs(10));
  }

  @Test
  @DisplayName("重连退避：负 attempt 按 0 处理")
  void reconnectDelayClampsNegativeAttempt() {
    assertTrue(DingTalkChannelAdapter.reconnectDelayMs(-1) >= 2_000L);
  }

  @Test
  @DisplayName("重连路径复用 MessageSender，sessionWebhook 映射不丢失")
  void outboundStackReusedAcrossReconnectInit() throws Exception {
    ChannelConfig config =
        new ChannelConfig("ops-dingtalk", "dingtalk", "cid", "csec", "demo-agent", true);
    DingTalkChannelAdapter adapter =
        new DingTalkChannelAdapter(
            config,
            new ProfileRegistry(),
            mock(InboundMessageService.class),
            (OutboundGuard) url -> {});

    Method ensure = DingTalkChannelAdapter.class.getDeclaredMethod("ensureOutboundStack");
    ensure.setAccessible(true);
    Field senderField = DingTalkChannelAdapter.class.getDeclaredField("sender");
    senderField.setAccessible(true);

    ensure.invoke(adapter);
    DingTalkMessageSender first = (DingTalkMessageSender) senderField.get(adapter);
    first.rememberSession("conv-1", "https://oapi.dingtalk.com/robot/send?token=t", null);

    ensure.invoke(adapter);
    DingTalkMessageSender second = (DingTalkMessageSender) senderField.get(adapter);
    assertSame(first, second);
  }
}
