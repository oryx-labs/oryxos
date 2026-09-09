package io.oryxos.channel.mattermost;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.oryxos.core.channel.ChannelConfig;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MattermostChannelAdapterTest {

  @Test
  @DisplayName("extra.access_token 优先于 app_secret 发帖")
  void extraAccessTokenWins() {
    ChannelConfig config =
        new ChannelConfig(
            "ops-mattermost",
            "mattermost",
            "oryxbot",
            "hook-token",
            "demo-agent",
            true,
            Map.of("base_url", "http://127.0.0.1:8065", "access_token", "pat-token"));
    assertEquals("pat-token", MattermostChannelAdapter.postToken(config));
  }

  @Test
  @DisplayName("无 extra.access_token 时回退 app_secret")
  void fallbackAppSecret() {
    ChannelConfig config =
        new ChannelConfig(
            "ops-mattermost",
            "mattermost",
            "oryxbot",
            "hook-token",
            "demo-agent",
            true,
            Map.of("base_url", "http://127.0.0.1:8065"));
    assertEquals("hook-token", MattermostChannelAdapter.postToken(config));
  }
}
