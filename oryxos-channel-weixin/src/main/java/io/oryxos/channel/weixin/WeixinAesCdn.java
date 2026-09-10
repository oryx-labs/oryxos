package io.oryxos.channel.weixin;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * iLink CDN：AES-128-ECB + PKCS7（对齐 Hermes {@code _aes128_ecb_decrypt} / {@code _parse_aes_key}）。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"CIPHER_INTEGRITY", "ECB_MODE"},
    justification =
        "腾讯 iLink Bot 媒体 CDN 协议固定 AES-128-ECB（Hermes/OpenClaw 同路），无法改用 AEAD；"
            + "密钥为每文件独立 aes_key，仅解密平台侧已加密的临时资源。")
final class WeixinAesCdn {

  private static final String TRANSFORMATION = "AES/ECB/NoPadding";
  private static final int KEY_LEN = 16;
  private static final int HEX_KEY_ASCII_LEN = 32;

  /** 32 位 hex AES key（Hermes image_item.aeskey / base64(hex) 解码后）。 */
  private static final String HEX_AES_KEY_PATTERN = "(?i)[0-9a-f]{32}";

  private WeixinAesCdn() {}

  static boolean isHexAesKey(String value) {
    return value != null && value.matches(HEX_AES_KEY_PATTERN);
  }

  static byte[] decryptIfNeeded(byte[] ciphertext, String aesKeyB64) throws Exception {
    if (ciphertext == null) {
      return new byte[0];
    }
    if (aesKeyB64 == null || aesKeyB64.isBlank()) {
      return ciphertext;
    }
    return decrypt(ciphertext, parseAesKey(aesKeyB64));
  }

  static byte[] parseAesKey(String aesKeyB64) {
    byte[] decoded = Base64.getDecoder().decode(aesKeyB64.strip());
    if (decoded.length == KEY_LEN) {
      return decoded;
    }
    if (decoded.length == HEX_KEY_ASCII_LEN) {
      String text = new String(decoded, StandardCharsets.US_ASCII);
      if (isHexAesKey(text)) {
        return hexToBytes(text);
      }
    }
    throw new IllegalArgumentException(
        "unexpected aes_key format (" + decoded.length + " decoded bytes)");
  }

  static byte[] decrypt(byte[] ciphertext, byte[] key) throws Exception {
    if (key == null || key.length != KEY_LEN) {
      throw new IllegalArgumentException("AES key must be 16 bytes");
    }
    Cipher cipher = Cipher.getInstance(TRANSFORMATION);
    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
    byte[] padded = cipher.doFinal(ciphertext);
    return pkcs7Unpad(padded);
  }

  private static byte[] pkcs7Unpad(byte[] padded) {
    if (padded == null || padded.length == 0) {
      return padded == null ? new byte[0] : padded;
    }
    int padLen = padded[padded.length - 1] & 0xff;
    if (padLen < 1 || padLen > KEY_LEN || padLen > padded.length) {
      return padded;
    }
    for (int i = 1; i <= padLen; i++) {
      if ((padded[padded.length - i] & 0xff) != padLen) {
        return padded;
      }
    }
    byte[] out = new byte[padded.length - padLen];
    System.arraycopy(padded, 0, out, 0, out.length);
    return out;
  }

  private static byte[] hexToBytes(String hex) {
    int n = hex.length() / 2;
    byte[] out = new byte[n];
    for (int i = 0; i < n; i++) {
      out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
    }
    return out;
  }
}
