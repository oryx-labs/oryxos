package io.oryxos.core.channel;

import java.util.ArrayList;
import java.util.List;

/**
 * 出站文本分段：按平台单条上限切分，且**不把代理对劈成两半**。
 *
 * <p>出站分段一直是各渠道各写一份（飞书 4000 / 企微 3500 / 钉钉 3500 / Slack 3500 / Discord 1900 / QQ /
 * Telegram），七份实现的循环体相同，且都把切点当作纯字符下标：
 *
 * <pre>{@code
 * for (int i = 0; i < text.length(); i += chunkSize) {
 *   parts.add(text.substring(i, Math.min(text.length(), i + chunkSize)));
 * }
 * }</pre>
 *
 * <p>切点落在代理对中间时（emoji、非 BMP 汉字都占两个 char），前一段以孤立高代理结尾、后一段以孤立低代理 开头。Java 的 UTF-8 编码把无法配对的代理写成 {@code
 * '?'}(0x3F)，于是用户看到「😀」变成「??」—— 一段回复的中间凭空多两个问号，而类注释写的是「内容不丢」。切点往回收一格即可。
 */
public final class OutboundTextSegments {

  private OutboundTextSegments() {}

  /**
   * 按字符数分段，顺序保持；空文本返回一个空段（保持“必有回复”语义）。
   *
   * @param text 待分段文本，{@code null} 视同空
   * @param chunkSize 每段上限（字符数）；{@code <= 0} 时不分段
   */
  public static List<String> split(String text, int chunkSize) {
    if (text == null || text.isEmpty()) {
      return List.of("");
    }
    if (chunkSize <= 0) {
      return List.of(text);
    }
    List<String> parts = new ArrayList<>();
    int i = 0;
    while (i < text.length()) {
      int end = Math.min(text.length(), i + chunkSize);
      // 末端停在一个高代理上，说明它和下一个 char 组成一个字符 —— 留给下一段。
      if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
        end--;
      }
      if (end <= i) {
        // 退无可退（chunkSize 为 1 且当前就是高代理）：宁可把一个字符拆开，也不能原地打转。
        end = Math.min(text.length(), i + chunkSize);
      }
      parts.add(text.substring(i, end));
      i = end;
    }
    return parts;
  }

  /**
   * 截断到至多 {@code max} 个字符，且不劈开代理对。
   *
   * @param text 待截断文本，{@code null} 视同空
   * @param max 上限（字符数）；{@code <= 0} 时返回空串
   */
  public static String truncate(String text, int max) {
    if (text == null || max <= 0) {
      return "";
    }
    if (text.length() <= max) {
      return text;
    }
    int end = max;
    if (Character.isHighSurrogate(text.charAt(end - 1))) {
      end--;
    }
    return text.substring(0, end);
  }
}
