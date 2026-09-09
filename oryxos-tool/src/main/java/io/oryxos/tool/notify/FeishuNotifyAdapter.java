package io.oryxos.tool.notify;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 飞书 / Lark 自定义机器人（type: feishu）。
 *
 * <p>二者协议相同、仅域名不同（open.feishu.cn / open.larksuite.com），URL 来自配置故一个实现覆盖两者。
 *
 * <p>默认 body：{@code {"msg_type":"text","content":{"text":"..."}}}。 {@code config.format=post}
 * 时发官方富文本 post（把 content 整段放入 text 节点）。 {@code format=markdown} 时发互动卡片 {@code lark_md} 以渲染
 * Markdown。 {@code format=interactive}/{@code card} 时发简易互动卡片（header + lark_md 正文）。
 *
 * <p>签名校验为可选项：config 含 {@code secret} 时按官方算法把 {@code timestamp}+{@code sign} 写入 JSON 体（秒级时间戳；
 * 与钉钉「拼到 URL」不同）。
 *
 * <p>出网经 {@link NotifyPoster}：禁自动重定向并每跳复检域名白名单。
 */
public class FeishuNotifyAdapter implements NotifyChannelAdapter {

  private static final String FORMAT_TEXT = "text";
  private static final String FORMAT_POST = "post";
  private static final String FORMAT_MARKDOWN = "markdown";
  private static final String FORMAT_INTERACTIVE = "interactive";
  private static final String FORMAT_CARD = "card";
  private static final String CONFIG_FORMAT = "format";
  private static final String CONFIG_MSG_TYPE = "msg_type";
  private static final String CONFIG_TITLE = "title";
  private static final String DEFAULT_TITLE = "notify";
  private static final String MARKDOWN_HEADING_PREFIX = "#";
  private static final int TITLE_MAX_LEN = 64;

  private final NotifyPoster poster;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "NotifyPoster is a Spring singleton shared by notify adapters; storing the reference is intentional.")
  public FeishuNotifyAdapter(NotifyPoster poster) {
    this.poster = poster;
  }

  @Override
  public void send(NotifyTarget target, String content) {
    String url = target.config().get("url");
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("feishu 渠道缺少 url 配置（notify_channels 条目需要 url 键）");
    }
    String format = resolveFormat(target.config());
    Map<String, Object> body;
    if (FORMAT_POST.equals(format)) {
      body = buildPostBody(target.config(), content);
    } else if (FORMAT_MARKDOWN.equals(format)) {
      body = buildInteractiveBody(target.config(), content);
    } else if (FORMAT_INTERACTIVE.equals(format) || FORMAT_CARD.equals(format)) {
      body = buildInteractiveBody(target.config(), content);
    } else if (FORMAT_TEXT.equals(format)) {
      body = new LinkedHashMap<>();
      body.put(CONFIG_MSG_TYPE, FORMAT_TEXT);
      body.put("content", Map.of("text", content));
    } else {
      throw new IllegalArgumentException(
          "feishu 不支持 format/msg_type="
              + format
              + "（当前支持: text, post, markdown, interactive, card）");
    }
    maybeSign(body, target.config().get("secret"));
    poster.postJson(url, body);
  }

  private static Map<String, Object> buildPostBody(Map<String, String> config, String content) {
    String title = resolveTitle(config, content);
    Map<String, Object> zhCn = new LinkedHashMap<>();
    zhCn.put(CONFIG_TITLE, title);
    zhCn.put("content", List.of(List.of(Map.of("tag", "text", "text", content))));
    Map<String, Object> post = Map.of("zh_cn", zhCn);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put(CONFIG_MSG_TYPE, FORMAT_POST);
    body.put("content", Map.of(FORMAT_POST, post));
    return body;
  }

  private static Map<String, Object> buildInteractiveBody(
      Map<String, String> config, String content) {
    String title = resolveTitle(config, content);
    Map<String, Object> card = new LinkedHashMap<>();
    card.put("header", Map.of("title", Map.of("tag", "plain_text", "content", title)));
    card.put(
        "elements",
        List.of(Map.of("tag", "div", "text", Map.of("tag", "lark_md", "content", content))));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put(CONFIG_MSG_TYPE, FORMAT_INTERACTIVE);
    body.put("card", card);
    return body;
  }

  /**
   * 飞书自定义机器人加签：timestamp（秒）+ sign 写入 JSON 体。sign = Base64(HmacSHA256(key=timestamp+"\\n"+secret,
   * data=空字节))。
   */
  private static void maybeSign(Map<String, Object> body, String secret) {
    if (secret == null || secret.isBlank()) {
      return;
    }
    long timestampSec = System.currentTimeMillis() / 1000L;
    String stringToSign = timestampSec + "\n" + secret;
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(stringToSign.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      String sign = Base64.getEncoder().encodeToString(mac.doFinal(new byte[0]));
      body.put("timestamp", String.valueOf(timestampSec));
      body.put("sign", sign);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("飞书加签计算失败", e);
    }
  }

  /** {@code format} 优先，其次兼容 {@code msg_type}；缺省 text。比较用 Locale.ROOT 小写。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "format/msg_type are ASCII protocol tokens; Locale.ROOT lowercasing is the correct case-fold.")
  private static String resolveFormat(Map<String, String> config) {
    String format = config.get(CONFIG_FORMAT);
    if (format == null || format.isBlank()) {
      format = config.get(CONFIG_MSG_TYPE);
    }
    if (format == null || format.isBlank()) {
      return FORMAT_TEXT;
    }
    return format.toLowerCase(Locale.ROOT).strip();
  }

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
}
