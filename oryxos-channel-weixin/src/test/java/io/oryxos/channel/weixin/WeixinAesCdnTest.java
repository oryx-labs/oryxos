package io.oryxos.channel.weixin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WeixinAesCdnTest {

  @Test
  @DisplayName("AES-128-ECB PKCS7 往返")
  void roundTrip() throws Exception {
    byte[] key = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    byte[] plain = "hello-weixin-media".getBytes(StandardCharsets.UTF_8);
    byte[] cipher = encryptPkcs7(plain, key);
    String aesB64 = Base64.getEncoder().encodeToString(key);
    assertArrayEquals(plain, WeixinAesCdn.decryptIfNeeded(cipher, aesB64));
  }

  @Test
  @DisplayName("aes_key 为 base64(hex 字符串)")
  void hexAsciiKey() throws Exception {
    String hex = "0123456789abcdef0123456789abcdef";
    byte[] key = hexToBytes(hex);
    byte[] plain = "abc".getBytes(StandardCharsets.UTF_8);
    byte[] cipher = encryptPkcs7(plain, key);
    String aesB64 = Base64.getEncoder().encodeToString(hex.getBytes(StandardCharsets.US_ASCII));
    assertArrayEquals(plain, WeixinAesCdn.decryptIfNeeded(cipher, aesB64));
    assertEquals(16, WeixinAesCdn.parseAesKey(aesB64).length);
  }

  private static byte[] encryptPkcs7(byte[] plain, byte[] key) throws Exception {
    int pad = 16 - (plain.length % 16);
    byte[] padded = new byte[plain.length + pad];
    System.arraycopy(plain, 0, padded, 0, plain.length);
    for (int i = plain.length; i < padded.length; i++) {
      padded[i] = (byte) pad;
    }
    Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
    c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
    return c.doFinal(padded);
  }

  private static byte[] hexToBytes(String hex) {
    byte[] out = new byte[hex.length() / 2];
    for (int i = 0; i < out.length; i++) {
      out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
    }
    return out;
  }
}
