package io.oryxos.core.channel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 回调签名的比较。
 *
 * <p>签名是攻击者完全控制的输入：企微/公众号/小程序把它放在回调 URL 的 {@code msg_signature} 里，抖音放在 {@code X-Douyin-Signature}
 * 头里。用 {@link String#equals} 比较会在第一个不同的 字符处返回 —— 比较耗时随「前缀命中长度」单调，构成逐字符的时序预言机；对签名而言猜中即等价于 伪造成功。
 *
 * <p>本仓库别处的校验点（{@code A2aProperties}、{@code ApiKeyService}、 {@code KnowledgeIndexService}）已经用
 * {@link MessageDigest#isEqual}，这里把渠道侧也统一到 同一口径。
 */
public final class SignatureComparison {

  private SignatureComparison() {}

  /**
   * 以常量时间比较两个签名串。不做大小写归一——调用方各自决定（抖音的 {@code X-Douyin-Signature} 大小写不固定，需先折叠；微信的 {@code
   * msg_signature} 协议里恒为小写 hex）。
   *
   * <p>{@code null} 视同空串。按 ASCII 取字节：非 ASCII 字符会落到 {@code ?}（0x3F），而 hex 签名 里不可能出现它，所以不会造成误判。
   *
   * @param left 收到的签名
   * @param right 期望的签名
   */
  public static boolean constantTimeEquals(String left, String right) {
    return MessageDigest.isEqual(asciiBytes(left), asciiBytes(right));
  }

  private static byte[] asciiBytes(String value) {
    return value == null ? new byte[0] : value.getBytes(StandardCharsets.US_ASCII);
  }
}
