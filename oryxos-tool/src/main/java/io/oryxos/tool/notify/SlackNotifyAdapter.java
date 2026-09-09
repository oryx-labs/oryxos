package io.oryxos.tool.notify;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Slack 出站通知（type: slack）。
 *
 * <p>两种投递：Incoming Webhook（{@code config.url}）发 {@code {"text":"..."}}；或 Bot Token（{@code
 * config.token} + {@code config.channel_id}）走 {@code chat.postMessage}。
 */
public class SlackNotifyAdapter implements NotifyChannelAdapter {

  static final String API_POST_MESSAGE = "https://slack.com/api/chat.postMessage";

  private final NotifyPoster poster;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "NotifyPoster is a Spring singleton shared by notify adapters; storing the reference is intentional.")
  public SlackNotifyAdapter(NotifyPoster poster) {
    this.poster = poster;
  }

  @Override
  public void send(NotifyTarget target, String content) {
    String url = target.config().get("url");
    if (url != null && !url.isBlank()) {
      poster.postJson(url, Map.of("text", content));
      return;
    }
    String token = target.config().get("token");
    String channelId = target.config().get("channel_id");
    if (token == null || token.isBlank() || channelId == null || channelId.isBlank()) {
      throw new IllegalArgumentException(
          "slack 渠道缺少 url，或缺少 token + channel_id（Incoming Webhook / chat.postMessage）");
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("channel", channelId);
    body.put("text", content);
    poster.postJson(API_POST_MESSAGE, body, Map.of("Authorization", "Bearer " + token));
  }
}
