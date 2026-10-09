package io.oryxos.core.channel;

import java.util.regex.Pattern;

/**
 * 各渠道「@ 提及」的识别与剥离。
 *
 * <p>{@link InboundMessage#content()} 的契约是「群聊已剥离 @ 机器人片段、其余 mention 已替换为
 * 人名」——也就是说，剥离只该拿走**指向本机器人的那一段**。用平台的中性提及模式 （Telegram/Mattermost 的 {@code @\w+}、Discord 的 {@code
 * <@\d+>}）会连别人的提及一起删， 而前者还会命中邮箱域名：{@code alice@gmail.com} 被吃掉 {@code @gmail.com} 之后只剩 {@code
 * alice}。
 *
 * <p>这里把「只匹配自己那一段」的边界规则收在一处，免得每个渠道各写一遍。
 */
public final class MentionStripping {

  private static final String WORD = "A-Za-z0-9_";

  private MentionStripping() {}

  /**
   * 匹配指向 {@code username} 的 at-提及，连同其后紧邻的空白。
   *
   * <p>前后都要求非词字符，所以 {@code ops@example.com} 里的 {@code @example} 不会被当成提及， {@code @bot} 也不会命中
   * {@code @bot2}。
   *
   * @param username 不含 {@code @} 前缀的机器人用户名；空则返回一个永不匹配的模式
   */
  public static Pattern atMention(String username) {
    if (username == null || username.isBlank()) {
      return Pattern.compile("(?!)");
    }
    return Pattern.compile(
        "(?i)(?<![" + WORD + "])@" + Pattern.quote(username.strip()) + "(?![" + WORD + "])\\s*");
  }

  /**
   * 匹配指向 {@code id} 的角括号提及（{@code <@123>} 与 {@code <@!123>}），连同其后紧邻的空白。
   *
   * @param id 机器人自身的平台标识；空则返回一个永不匹配的模式
   */
  public static Pattern angleMention(String id) {
    if (id == null || id.isBlank()) {
      return Pattern.compile("(?!)");
    }
    return Pattern.compile("<@!?" + Pattern.quote(id.strip()) + ">\\s*");
  }

  /**
   * 剥离 {@code pattern} 命中的全部片段并去掉首尾空白。
   *
   * @param text 待处理正文；{@code null} 视同空
   */
  public static String strip(String text, Pattern pattern) {
    if (text == null || text.isBlank()) {
      return "";
    }
    return pattern.matcher(text).replaceAll("").strip();
  }
}
