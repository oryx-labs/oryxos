package io.oryxos.tool.notify;

import java.util.Locale;
import java.util.Map;

/**
 * 企业微信群机器人（type: wecom）。
 *
 * <p>默认 body：{@code {"msgtype":"text","text":{"content":"..."}}}。 {@code config.format=markdown}（或
 * {@code msgtype=markdown}）时改为官方 markdown： {@code
 * {"msgtype":"markdown","markdown":{"content":"..."}}}。
 *
 * <p>注意这是"群机器人"档——"应用消息"（corpid/corpsecret 换 AccessToken）属扩展阶段，不在此。 {@code template_card}/{@code
 * news} 等留给后续 PR。
 *
 * <p>出网经 {@link NotifyPoster}：禁自动重定向并每跳复检域名白名单。
 */
public class WeComNotifyAdapter implements NotifyChannelAdapter {

  private static final String FORMAT_TEXT = "text";
  private static final String FORMAT_MARKDOWN = "markdown";
  private static final String CONFIG_FORMAT = "format";
  private static final String CONFIG_MSGTYPE = "msgtype";

  private final NotifyPoster poster;

  public WeComNotifyAdapter(NotifyPoster poster) {
    this.poster = poster;
  }

  @Override
  public void send(NotifyTarget target, String content) {
    String url = target.config().get("url");
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("wecom 渠道缺少 url 配置（notify_channels 条目需要 url 键）");
    }
    String format = resolveFormat(target.config());
    if (FORMAT_MARKDOWN.equals(format)) {
      poster.postJson(
          url,
          Map.of(CONFIG_MSGTYPE, FORMAT_MARKDOWN, FORMAT_MARKDOWN, Map.of("content", content)));
      return;
    }
    if (!FORMAT_TEXT.equals(format)) {
      throw new IllegalArgumentException(
          "wecom 不支持 format/msgtype=" + format + "（当前支持: text, markdown）");
    }
    poster.postJson(
        url, Map.of(CONFIG_MSGTYPE, FORMAT_TEXT, FORMAT_TEXT, Map.of("content", content)));
  }

  /** {@code format} 优先，其次兼容 {@code msgtype}；缺省 text。比较用 Locale.ROOT 小写。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "format/msgtype are ASCII protocol tokens; Locale.ROOT lowercasing is the correct case-fold.")
  private static String resolveFormat(Map<String, String> config) {
    String format = config.get(CONFIG_FORMAT);
    if (format == null || format.isBlank()) {
      format = config.get(CONFIG_MSGTYPE);
    }
    if (format == null || format.isBlank()) {
      return FORMAT_TEXT;
    }
    return format.toLowerCase(Locale.ROOT).strip();
  }
}
