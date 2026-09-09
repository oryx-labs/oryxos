package io.oryxos.tool.notify;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 钉钉自定义机器人（type: dingtalk）。
 *
 * <p>默认 body：{@code {"msgtype":"text","text":{"content":"..."}}}。 {@code config.format=markdown}（或
 * {@code msgtype=markdown}）时改为官方 markdown： {@code
 * {"msgtype":"markdown","markdown":{"title":"...","text":"..."}}}。 {@code format=actionCard}（或
 * {@code action_card}）时发整体跳转卡片，需渠道 config 提供 {@code single_url}（可选 {@code single_title}）。
 *
 * <p>钉钉机器人必须启用一种安全设置："自定义关键词"最省事（把关键词写进 Skill 输出要求即可，本实现零处理）；若群里开的是"加签"， 在 config 里配 {@code
 * secret}（走 ${ENV} 占位），发送时按官方算法拼 timestamp+sign 到 URL。
 *
 * <p>出网经 {@link NotifyPoster}：禁自动重定向并每跳复检域名白名单。
 */
public class DingTalkNotifyAdapter implements NotifyChannelAdapter {

  private static final String FORMAT_TEXT = "text";
  private static final String FORMAT_MARKDOWN = "markdown";
  private static final String FORMAT_ACTION_CARD = "actioncard";
  private static final String FORMAT_ACTION_CARD_SNAKE = "action_card";
  private static final String MSGTYPE_ACTION_CARD = "actionCard";
  private static final String CONFIG_FORMAT = "format";
  private static final String CONFIG_MSGTYPE = "msgtype";
  private static final String CONFIG_TITLE = "title";
  private static final String CONFIG_SINGLE_TITLE = "single_title";
  private static final String CONFIG_SINGLE_URL = "single_url";
  private static final String DEFAULT_TITLE = "notify";
  private static final String DEFAULT_SINGLE_TITLE = "查看详情";
  private static final String MARKDOWN_HEADING_PREFIX = "#";
  private static final int TITLE_MAX_LEN = 64;

  private final NotifyPoster poster;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "NotifyPoster is a Spring singleton shared by notify adapters; storing the reference is intentional.")
  public DingTalkNotifyAdapter(NotifyPoster poster) {
    this.poster = poster;
  }

  @Override
  public void send(NotifyTarget target, String content) {
    String url = target.config().get("url");
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("dingtalk 渠道缺少 url 配置（notify_channels 条目需要 url 键）");
    }
    String secret = target.config().get("secret");
    if (secret != null && !secret.isBlank()) {
      url = appendSignature(url, secret);
    }
    String format = resolveFormat(target.config());
    if (FORMAT_MARKDOWN.equals(format)) {
      String title = resolveTitle(target.config(), content);
      poster.postJson(
          url,
          Map.of(
              CONFIG_MSGTYPE,
              FORMAT_MARKDOWN,
              FORMAT_MARKDOWN,
              Map.of(CONFIG_TITLE, title, "text", content)));
      return;
    }
    if (FORMAT_ACTION_CARD.equals(format)) {
      poster.postJson(url, buildActionCardBody(target.config(), content));
      return;
    }
    if (!FORMAT_TEXT.equals(format)) {
      throw new IllegalArgumentException(
          "dingtalk 不支持 format/msgtype=" + format + "（当前支持: text, markdown, actionCard）");
    }
    poster.postJson(
        url, Map.of(CONFIG_MSGTYPE, FORMAT_TEXT, FORMAT_TEXT, Map.of("content", content)));
  }

  private static Map<String, Object> buildActionCardBody(
      Map<String, String> config, String content) {
    String singleUrl = config.get(CONFIG_SINGLE_URL);
    if (singleUrl == null || singleUrl.isBlank()) {
      throw new IllegalArgumentException(
          "dingtalk format=actionCard 需要渠道 config.single_url（整体跳转卡片）");
    }
    String singleTitle = config.get(CONFIG_SINGLE_TITLE);
    if (singleTitle == null || singleTitle.isBlank()) {
      singleTitle = DEFAULT_SINGLE_TITLE;
    }
    Map<String, Object> actionCard = new LinkedHashMap<>();
    actionCard.put(CONFIG_TITLE, resolveTitle(config, content));
    actionCard.put("text", content);
    actionCard.put("singleTitle", singleTitle.strip());
    actionCard.put("singleURL", singleUrl.strip());
    return Map.of(CONFIG_MSGTYPE, MSGTYPE_ACTION_CARD, MSGTYPE_ACTION_CARD, actionCard);
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
    String normalized = format.toLowerCase(Locale.ROOT).strip();
    if (FORMAT_ACTION_CARD_SNAKE.equals(normalized)
        || MSGTYPE_ACTION_CARD.toLowerCase(Locale.ROOT).equals(normalized)) {
      return FORMAT_ACTION_CARD;
    }
    return normalized;
  }

  /** 优先 config.title；否则取正文首行（去 markdown 标题标记），再截断。 */
  private static String resolveTitle(Map<String, String> config, String content) {
    String title = config.get(CONFIG_TITLE);
    if (title != null && !title.isBlank()) {
      return truncate(title.strip(), TITLE_MAX_LEN);
    }
    if (content == null || content.isBlank()) {
      return DEFAULT_TITLE;
    }
    String firstLine = content.strip().lines().findFirst().orElse(DEFAULT_TITLE).strip();
    while (firstLine.startsWith(MARKDOWN_HEADING_PREFIX)) {
      firstLine = firstLine.substring(MARKDOWN_HEADING_PREFIX.length()).strip();
    }
    if (firstLine.isBlank()) {
      return DEFAULT_TITLE;
    }
    return truncate(firstLine, TITLE_MAX_LEN);
  }

  private static String truncate(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max);
  }

  /** 钉钉"加签"安全设置：sign = urlEncode(base64(HmacSHA256(timestamp + "\n" + secret, secret)))。 */
  private static String appendSignature(String url, String secret) {
    long timestamp = System.currentTimeMillis();
    String stringToSign = timestamp + "\n" + secret;
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      String sign =
          URLEncoder.encode(
              Base64.getEncoder()
                  .encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8))),
              StandardCharsets.UTF_8);
      String separator = url.contains("?") ? "&" : "?";
      return url + separator + "timestamp=" + timestamp + "&sign=" + sign;
    } catch (GeneralSecurityException e) {
      // HmacSHA256 是 JDK 必备算法，走到这里说明运行环境异常——显式失败胜过发一条必被拒的请求
      throw new IllegalStateException("钉钉加签计算失败", e);
    }
  }
}
