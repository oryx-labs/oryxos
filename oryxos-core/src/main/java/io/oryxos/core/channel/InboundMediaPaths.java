package io.oryxos.core.channel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 入站媒体路径段清理（messageId / fileKey 等）。 */
public final class InboundMediaPaths {

  private static final String FALLBACK_SEGMENT = "x";
  private static final char PATH_SAFE_REPLACEMENT = '_';
  private static final int MAX_SEGMENT_LEN = 96;

  /** 超长段截断后追加的消歧后缀长度（含前导 '_'）。 */
  private static final int DIGEST_SUFFIX_LEN = 9;

  private InboundMediaPaths() {}

  /**
   * 把不可信文本变成一个可以安全 {@code resolve} 到目录下的单一路径段。
   *
   * <p>调用方一律写成 {@code mediaRoot.resolve(safeSegment(messageId))}，所以这个方法的契约是：
   * 结果必须是一个**名字**，而不是一个指向别处的**引用**。白名单 {@code [A-Za-z0-9._-]} 只保证了 结果里没有分隔符与上跳序列，但 {@code "."} 与
   * {@code ".." } 本身就在白名单里，且它们恰恰是 目录引用——{@code resolve("..")} 会落到 {@code mediaRoot} 的上一层。
   *
   * <p>同理，截断不能只取前缀：只有尾部不同的两个 id 会塌成同一个名字，两个消息于是共用同一目录， 文件名再来一次截断就会互相覆盖。
   *
   * @param raw 不可信输入（messageId / fileKey / downloadCode 等），可为 null
   * @return 一个安全的单一路径段；非法或不可用时返回 {@code "x"}
   */
  public static String safeSegment(String raw) {
    if (raw == null || raw.isBlank()) {
      return FALLBACK_SEGMENT;
    }
    String cleaned = raw.replaceAll("[^a-zA-Z0-9._-]", String.valueOf(PATH_SAFE_REPLACEMENT));
    if (cleaned.isBlank() || cleaned.chars().allMatch(ch -> ch == PATH_SAFE_REPLACEMENT)) {
      return FALLBACK_SEGMENT;
    }
    // 全点串（"." / ".." / "..."）只由白名单字符组成，却都是目录引用而不是名字。
    if (cleaned.chars().allMatch(ch -> ch == '.')) {
      return FALLBACK_SEGMENT;
    }
    if (cleaned.length() > MAX_SEGMENT_LEN) {
      // 摘要取自【原始输入】：按截断后的前缀算，两个只在尾部不同的 id 仍会得到同一个后缀。
      String digest = digest8(raw);
      cleaned = cleaned.substring(0, MAX_SEGMENT_LEN - DIGEST_SUFFIX_LEN) + "_" + digest;
    }
    return cleaned;
  }

  /** 8 位十六进制摘要，足以在同一目录内区分同时存在的消息 id。 */
  private static String digest8(String value) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash, 0, 4);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 是 JDK 必备算法，此分支不可达；退回 hashCode 也保持确定性。
      return String.format("%08x", value.hashCode());
    }
  }

  public static String sanitizeLog(String value) {
    return value == null
        ? ""
        : value.replace('\r', PATH_SAFE_REPLACEMENT).replace('\n', PATH_SAFE_REPLACEMENT);
  }
}
