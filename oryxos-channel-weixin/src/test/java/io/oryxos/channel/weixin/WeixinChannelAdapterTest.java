package io.oryxos.channel.weixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.InboundMessageService;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WeixinChannelAdapterTest {

  @Test
  @DisplayName("未启动 sendReply → 拒绝")
  void sendBeforeStart() {
    ProfileRegistry profiles = mock(ProfileRegistry.class);
    when(profiles.get("demo-agent")).thenReturn(Optional.of(mock(Profile.class)));
    ChannelConfig config =
        new ChannelConfig("ops-weixin", "weixin", "bot-acc", "tok", "demo-agent", true, Map.of());
    WeixinChannelAdapter adapter =
        new WeixinChannelAdapter(config, profiles, mock(InboundMessageService.class), url -> {});
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> adapter.sendReply("user:u1", "hi", null));
    assertTrue(e.getMessage().contains("尚未启动"));
  }

  @Test
  @DisplayName("context_token 缺失硬拒绝")
  void contextTokenRequired() {
    WeixinContextTokenStore store = new WeixinContextTokenStore();
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> store.require("u1"));
    assertTrue(e.getMessage().contains("context_token"));
    store.remember("u1", "ctx");
    assertEquals("ctx", store.require("u1"));
  }
}
