package io.oryxos.channel.douyin;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** {@code X-Douyin-Signature} = sha1(client_secret + rawBody)。 */
final class DouyinWebhookSignature {

  private DouyinWebhookSignature() {}

  static boolean matches(String clientSecret, String rawBody, String header) {
    if (clientSecret == null || header == null || header.isBlank()) {
      return false;
    }
    String expected = sha1Hex(clientSecret + (rawBody == null ? "" : rawBody));
    return asciiLower(header).equals(asciiLower(expected));
  }

  @SuppressFBWarnings(
      value = "WEAK_MESSAGE_DIGEST_SHA1",
      justification = "抖音开放平台 Webhook 验签协议强制 sha1(client_secret + body)")
  static String sha1Hex(String payload) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-1");
      byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-1 unavailable", e);
    }
  }

  private static String asciiLower(String value) {
    char[] chars = value.toCharArray();
    for (int i = 0; i < chars.length; i++) {
      char c = chars[i];
      if (c >= 'A' && c <= 'Z') {
        chars[i] = (char) (c + ('a' - 'A'));
      }
    }
    return new String(chars);
  }
}
