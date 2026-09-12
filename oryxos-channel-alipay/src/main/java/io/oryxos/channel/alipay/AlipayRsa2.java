package io.oryxos.channel.alipay;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 支付宝生活号网关 / OpenAPI：RSA2（SHA256withRSA）签名与验签。
 *
 * <p>密钥支持 PEM 或裸 Base64（PKCS#8 私钥 / X.509 公钥）。
 */
final class AlipayRsa2 {

  static final String SIGN_TYPE = "RSA2";
  private static final String ALGORITHM = "SHA256withRSA";

  private final PrivateKey privateKey;
  private final PublicKey alipayPublicKey;
  private final String appPublicKeyPlain;

  AlipayRsa2(String appPrivateKey, String alipayPublicKey, String appPublicKey) {
    if (appPrivateKey == null || appPrivateKey.isBlank()) {
      throw new IllegalArgumentException("支付宝应用私钥为空");
    }
    if (alipayPublicKey == null || alipayPublicKey.isBlank()) {
      throw new IllegalArgumentException("支付宝公钥为空");
    }
    if (appPublicKey == null || appPublicKey.isBlank()) {
      throw new IllegalArgumentException("应用公钥为空（verifygw 回执需要）");
    }
    this.privateKey = parsePrivateKey(appPrivateKey);
    this.alipayPublicKey = parsePublicKey(alipayPublicKey);
    this.appPublicKeyPlain = stripKeyMaterial(appPublicKey);
  }

  String appPublicKeyPlain() {
    return appPublicKeyPlain;
  }

  boolean verify(Map<String, String> params) {
    if (params == null || params.isEmpty()) {
      return false;
    }
    String sign = params.get("sign");
    if (sign == null || sign.isBlank()) {
      return false;
    }
    String content = signContent(params, true);
    return verifyContent(content, sign, StandardCharsets.UTF_8);
  }

  /** 验签；charset 影响待签字符串字节（生活号网关常为 GBK）。 */
  boolean verify(Map<String, String> params, Charset charset) {
    if (params == null || params.isEmpty()) {
      return false;
    }
    String sign = params.get("sign");
    if (sign == null || sign.isBlank()) {
      return false;
    }
    Charset cs = charset == null ? StandardCharsets.UTF_8 : charset;
    String content = signContent(params, true);
    return verifyContent(content, sign, cs);
  }

  String sign(Map<String, String> params, Charset charset) {
    Charset cs = charset == null ? StandardCharsets.UTF_8 : charset;
    String content = signContent(params, false);
    return signContentBytes(content, cs);
  }

  String signRaw(String content, Charset charset) {
    Charset cs = charset == null ? StandardCharsets.UTF_8 : charset;
    return signContentBytes(content == null ? "" : content, cs);
  }

  private boolean verifyContent(String content, String signBase64, Charset charset) {
    try {
      Signature signature = Signature.getInstance(ALGORITHM);
      signature.initVerify(alipayPublicKey);
      signature.update(content.getBytes(charset));
      return signature.verify(Base64.getDecoder().decode(signBase64));
    } catch (Exception e) {
      return false;
    }
  }

  private String signContentBytes(String content, Charset charset) {
    try {
      Signature signature = Signature.getInstance(ALGORITHM);
      signature.initSign(privateKey);
      signature.update(content.getBytes(charset));
      return Base64.getEncoder().encodeToString(signature.sign());
    } catch (Exception e) {
      throw new IllegalStateException("支付宝 RSA2 签名失败: " + e.getMessage(), e);
    }
  }

  /**
   * 拼接待签串：参数名 ASCII 升序，{@code key=value&...}；跳过空值；验签时排除 {@code sign}。
   *
   * <p>{@code sign_type} 参与拼串（生活号网关验签惯例）。
   */
  static String signContent(Map<String, String> params, boolean forVerify) {
    TreeMap<String, String> sorted = new TreeMap<>();
    for (Map.Entry<String, String> e : params.entrySet()) {
      String key = e.getKey();
      String value = e.getValue();
      if (key == null || key.isBlank() || value == null || value.isEmpty()) {
        continue;
      }
      if (forVerify && "sign".equals(key)) {
        continue;
      }
      if (!forVerify && "sign".equals(key)) {
        continue;
      }
      sorted.put(key, value);
    }
    List<String> parts = new ArrayList<>(sorted.size());
    for (Map.Entry<String, String> e : sorted.entrySet()) {
      parts.add(e.getKey() + "=" + e.getValue());
    }
    return String.join("&", parts);
  }

  private static PrivateKey parsePrivateKey(String material) {
    byte[] der = Base64.getDecoder().decode(stripKeyMaterial(material));
    try {
      return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    } catch (Exception pkcs8Fail) {
      try {
        return KeyFactory.getInstance("RSA")
            .generatePrivate(new PKCS8EncodedKeySpec(wrapPkcs1ToPkcs8(der)));
      } catch (Exception pkcs1Fail) {
        throw new IllegalArgumentException(
            "无法解析支付宝应用私钥（需 PKCS#8 或 PKCS#1 Base64/PEM）: " + pkcs8Fail.getMessage(), pkcs8Fail);
      }
    }
  }

  /** 将 PKCS#1 RSAPrivateKey DER 包成 PKCS#8 PrivateKeyInfo。 */
  private static byte[] wrapPkcs1ToPkcs8(byte[] pkcs1) {
    byte[] algId =
        new byte[] {
          0x30,
          0x0d,
          0x06,
          0x09,
          0x2a,
          (byte) 0x86,
          0x48,
          (byte) 0x86,
          (byte) 0xf7,
          0x0d,
          0x01,
          0x01,
          0x01,
          0x05,
          0x00
        };
    byte[] octet = encodeTlv(0x04, pkcs1);
    byte[] version = new byte[] {0x02, 0x01, 0x00};
    byte[] seqInner = new byte[version.length + algId.length + octet.length];
    System.arraycopy(version, 0, seqInner, 0, version.length);
    System.arraycopy(algId, 0, seqInner, version.length, algId.length);
    System.arraycopy(octet, 0, seqInner, version.length + algId.length, octet.length);
    return encodeTlv(0x30, seqInner);
  }

  private static byte[] encodeTlv(int tag, byte[] value) {
    int len = value.length;
    byte[] lenBytes;
    if (len < 0x80) {
      lenBytes = new byte[] {(byte) len};
    } else if (len <= 0xff) {
      lenBytes = new byte[] {(byte) 0x81, (byte) len};
    } else {
      lenBytes = new byte[] {(byte) 0x82, (byte) ((len >> 8) & 0xff), (byte) (len & 0xff)};
    }
    byte[] out = new byte[1 + lenBytes.length + value.length];
    out[0] = (byte) tag;
    System.arraycopy(lenBytes, 0, out, 1, lenBytes.length);
    System.arraycopy(value, 0, out, 1 + lenBytes.length, value.length);
    return out;
  }

  private static PublicKey parsePublicKey(String material) {
    try {
      byte[] der = Base64.getDecoder().decode(stripKeyMaterial(material));
      return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    } catch (Exception e) {
      throw new IllegalArgumentException("无法解析支付宝公钥（需 X.509 Base64/PEM）: " + e.getMessage(), e);
    }
  }

  static String stripKeyMaterial(String material) {
    String raw = material.strip();
    StringBuilder sb = new StringBuilder(raw.length());
    for (String line : raw.split("\\R")) {
      String t = line.strip();
      if (t.isEmpty()) {
        continue;
      }
      String upper = t.toUpperCase(Locale.ROOT);
      if (upper.startsWith("-----BEGIN") || upper.startsWith("-----END")) {
        continue;
      }
      sb.append(t);
    }
    return sb.toString();
  }
}
