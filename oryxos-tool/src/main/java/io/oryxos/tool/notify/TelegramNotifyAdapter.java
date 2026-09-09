package io.oryxos.tool.notify;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Telegram Bot API 出站（type: telegram）。
 *
 * <p>{@code config.token} + {@code config.chat_id} 调用 {@code sendMessage}；若给了 {@code url} 则按
 * Incoming Webhook 形态 POST JSON（联调用）。
 */
public class TelegramNotifyAdapter implements NotifyChannelAdapter {

  static final String API_PREFIX = "https://api.telegram.org/bot";

  private final NotifyPoster poster;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "NotifyPoster is a Spring singleton shared by notify adapters; storing the reference is intentional.")
  public TelegramNotifyAdapter(NotifyPoster poster) {
    this.poster = poster;
  }

  @Override
  public void send(NotifyTarget target, String content) {
    String url = target.config().get("url");
    if (url != null && !url.isBlank()) {
      poster.postJson(
          url, Map.of("text", content, "chat_id", target.config().getOrDefault("chat_id", "")));
      return;
    }
    String token = target.config().get("token");
    String chatId = target.config().get("chat_id");
    if (token == null || token.isBlank() || chatId == null || chatId.isBlank()) {
      throw new IllegalArgumentException("telegram 渠道缺少 url，或缺少 token + chat_id");
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("chat_id", chatId);
    body.put("text", content);
    poster.postJson(API_PREFIX + token + "/sendMessage", body);
  }
}
