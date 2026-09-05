package io.oryxos.core.channel;

/** 入站媒体路径段清理（messageId / fileKey 等）。 */
public final class InboundMediaPaths {

  private static final String FALLBACK_SEGMENT = "x";
  private static final char PATH_SAFE_REPLACEMENT = '_';
  private static final int MAX_SEGMENT_LEN = 96;

  private InboundMediaPaths() {}

  public static String safeSegment(String raw) {
    if (raw == null || raw.isBlank()) {
      return FALLBACK_SEGMENT;
    }
    String cleaned = raw.replaceAll("[^a-zA-Z0-9._-]", String.valueOf(PATH_SAFE_REPLACEMENT));
    if (cleaned.length() > MAX_SEGMENT_LEN) {
      cleaned = cleaned.substring(0, MAX_SEGMENT_LEN);
    }
    if (cleaned.isBlank() || cleaned.chars().allMatch(ch -> ch == PATH_SAFE_REPLACEMENT)) {
      return FALLBACK_SEGMENT;
    }
    return cleaned;
  }

  public static String sanitizeLog(String value) {
    return value == null
        ? ""
        : value.replace('\r', PATH_SAFE_REPLACEMENT).replace('\n', PATH_SAFE_REPLACEMENT);
  }
}
