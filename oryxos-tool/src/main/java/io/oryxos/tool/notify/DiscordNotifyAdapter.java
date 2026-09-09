package io.oryxos.tool.notify;

import java.util.Map;

/**
 * Discord 出站通知（type: discord）。
 *
 * <p>两种投递：Incoming Webhook（{@code config.url}）发 {@code {"content":"..."}}；或 Bot Token（{@code
 * config.token} + {@code config.channel_id}）走 {@code POST /channels/{id}/messages}。
 */
public class DiscordNotifyAdapter implements NotifyChannelAdapter {

  static final String API_CHANNEL_MESSAGES = "https://discord.com/api/v10/channels/";

  private final NotifyPoster poster;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "NotifyPoster is a Spring singleton shared by notify adapters; storing the reference is intentional.")
  public DiscordNotifyAdapter(NotifyPoster poster) {
    this.poster = poster;
  }

  @Override
  public void send(NotifyTarget target, String content) {
    String url = target.config().get("url");
    if (url != null && !url.isBlank()) {
      poster.postJson(url, Map.of("content", content));
      return;
    }
    String token = target.config().get("token");
    String channelId = target.config().get("channel_id");
    if (token == null || token.isBlank() || channelId == null || channelId.isBlank()) {
      throw new IllegalArgumentException(
          "discord 渠道缺少 url，或缺少 token + channel_id（Incoming Webhook / Bot REST）");
    }
    poster.postJson(
        API_CHANNEL_MESSAGES + channelId + "/messages",
        Map.of("content", content),
        Map.of("Authorization", "Bot " + token));
  }
}
