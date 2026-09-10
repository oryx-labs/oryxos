package io.oryxos.tool.notify;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * QQ 官方 Bot 出站（type: qq）。
 *
 * <p>{@code config.token}（access_token）+ {@code group_openid} 或 {@code user_openid}；若给了 {@code url}
 * 则 POST 到该地址（联调用）。鉴权头 {@code Authorization: QQBot {token}}。
 */
public class QqNotifyAdapter implements NotifyChannelAdapter {

  static final String API_BASE = "https://api.bot.qq.com";

  private final NotifyPoster poster;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "NotifyPoster is a Spring singleton shared by notify adapters; storing the reference is intentional.")
  public QqNotifyAdapter(NotifyPoster poster) {
    this.poster = poster;
  }

  @Override
  public void send(NotifyTarget target, String content) {
    String token = target.config().get("token");
    String groupOpenid = target.config().get("group_openid");
    String userOpenid = target.config().get("user_openid");
    String url = target.config().get("url");
    if (url == null || url.isBlank()) {
      if (token == null || token.isBlank()) {
        throw new IllegalArgumentException("qq 渠道缺少 url，或缺少 token + group_openid/user_openid");
      }
      if (hasText(groupOpenid)) {
        url = API_BASE + "/v2/groups/" + groupOpenid.strip() + "/messages";
      } else if (hasText(userOpenid)) {
        url = API_BASE + "/v2/users/" + userOpenid.strip() + "/messages";
      } else {
        throw new IllegalArgumentException("qq 渠道缺少 url，或缺少 token + group_openid/user_openid");
      }
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("msg_type", 0);
    body.put("content", content);
    Map<String, String> headers = new LinkedHashMap<>();
    if (token != null && !token.isBlank()) {
      headers.put("Authorization", "QQBot " + token.strip());
    }
    poster.postJson(url, body, headers);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
