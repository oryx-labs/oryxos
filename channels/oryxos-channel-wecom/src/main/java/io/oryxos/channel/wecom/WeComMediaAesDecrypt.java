package io.oryxos.channel.wecom;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 企微智能机器人长连接入站媒体解密：AES-256-CBC，IV 为密钥前 16 字节，PKCS#7（填充长度可达 32）。
 *
 * <p>对齐官方 aibot SDK（{@code decrypt_file}）与文档「多媒体资源解密」。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "CIPHER_INTEGRITY",
    justification =
        "企微长连接多媒体协议固定为 AES-256-CBC（官方文档/SDK），无法改用带完整性的 AEAD；"
            + "密钥为每条消息独立 aeskey，仅用于解密平台侧已加密的临时资源。")
final class WeComMediaAesDecrypt {

  private static final int AES_BLOCK = 16;
  private static final int MAX_PKCS7_PAD = 32;
  private static final int AES_256_KEY_LEN = 32;
  private static final String TRANSFORMATION = "AES/CBC/NoPadding";

  private WeComMediaAesDecrypt() {}

  static byte[] decrypt(byte[] encrypted, String aesKeyBase64) {
    if (encrypted == null || encrypted.length == 0) {
      throw new IllegalArgumentException("encrypted_data is empty");
    }
    if (aesKeyBase64 == null || aesKeyBase64.isBlank()) {
      throw new IllegalArgumentException("aes_key must be a non-empty string");
    }
    byte[] key = decodeAesKey(aesKeyBase64.strip());
    if (key.length != AES_256_KEY_LEN) {
      throw new IllegalArgumentException("aes_key decoded length must be 32, got " + key.length);
    }
    byte[] iv = new byte[AES_BLOCK];
    System.arraycopy(key, 0, iv, 0, AES_BLOCK);
    byte[] aligned = alignToBlock(encrypted);
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
      byte[] decrypted = cipher.doFinal(aligned);
      return stripPkcs7(decrypted);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("WeCom media AES decrypt failed: " + e.getMessage(), e);
    }
  }

  private static byte[] decodeAesKey(String aesKeyBase64) {
    String padded = aesKeyBase64;
    int rem = padded.length() % 4;
    if (rem != 0) {
      padded = padded + "=".repeat(4 - rem);
    }
    return Base64.getDecoder().decode(padded.getBytes(StandardCharsets.US_ASCII));
  }

  private static byte[] alignToBlock(byte[] encrypted) {
    int rem = encrypted.length % AES_BLOCK;
    if (rem == 0) {
      return encrypted;
    }
    byte[] aligned = new byte[encrypted.length + (AES_BLOCK - rem)];
    System.arraycopy(encrypted, 0, aligned, 0, encrypted.length);
    return aligned;
  }

  private static byte[] stripPkcs7(byte[] decrypted) {
    if (decrypted.length == 0) {
      throw new IllegalStateException("Decrypted data is empty");
    }
    int padLen = decrypted[decrypted.length - 1] & 0xFF;
    if (padLen < 1 || padLen > MAX_PKCS7_PAD || padLen > decrypted.length) {
      throw new IllegalStateException("Invalid PKCS#7 padding value: " + padLen);
    }
    for (int i = decrypted.length - padLen; i < decrypted.length; i++) {
      if ((decrypted[i] & 0xFF) != padLen) {
        throw new IllegalStateException("Invalid PKCS#7 padding: padding bytes mismatch");
      }
    }
    byte[] plain = new byte[decrypted.length - padLen];
    System.arraycopy(decrypted, 0, plain, 0, plain.length);
    return plain;
  }
}
