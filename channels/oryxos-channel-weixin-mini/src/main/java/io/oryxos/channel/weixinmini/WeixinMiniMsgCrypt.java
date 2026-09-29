package io.oryxos.channel.weixinmini;

/**
 * 小程序消息推送加解密（WXBizMsgCrypt）：Token + EncodingAESKey + receiveId(小程序 AppId)。
 *
 * <p>GET URL 验签为明文 {@code signature}=SHA1(token,timestamp,nonce)；POST 安全模式与公众平台同族。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"CIPHER_INTEGRITY", "WEAK_MESSAGE_DIGEST_SHA1"},
    justification =
        "小程序消息推送协议固定 AES-256-CBC + msg_signature=SHA1（官方 WXBizMsgCrypt），"
            + "无法改用 AEAD/SHA-256；完整性依赖平台侧签名字段。")
final class WeixinMiniMsgCrypt {

  private static final int AES_BLOCK = 16;

  /** 公众平台官方 WXBizMsgCrypt PKCS#7 块长为 32（非 AES 16）。 */
  private static final int PKCS7_BLOCK = 32;

  private static final int RANDOM_LEN = 16;
  private static final int AES_KEY_LEN = 32;
  private static final int BASE64_PAD_MOD = 4;
  private static final int LENGTH_HEADER_BYTES = 4;
  private static final String TRANSFORMATION = "AES/CBC/NoPadding";
  private static final char BASE64_PAD = '=';
  private static final java.security.SecureRandom SECURE_RANDOM = new java.security.SecureRandom();

  private final byte[] aesKey;
  private final String token;
  private final String receiveId;

  WeixinMiniMsgCrypt(String token, String encodingAesKey, String receiveId) {
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("callback token 为空");
    }
    if (encodingAesKey == null || encodingAesKey.isBlank()) {
      throw new IllegalArgumentException("encoding_aes_key 为空");
    }
    if (receiveId == null || receiveId.isBlank()) {
      throw new IllegalArgumentException("appId/receiveId 为空");
    }
    String keyB64 = padBase64(encodingAesKey.strip());
    byte[] key = java.util.Base64.getDecoder().decode(keyB64);
    if (key.length != AES_KEY_LEN) {
      throw new IllegalArgumentException("encoding_aes_key 解码后须为 32 字节");
    }
    this.aesKey = key;
    this.token = token.strip();
    this.receiveId = receiveId.strip();
  }

  /** 小程序 GET URL 验签：{@code signature}=SHA1(sorted token,timestamp,nonce)，原样返回 echostr（不解密）。 */
  String verifyUrlPlain(String signature, String timestamp, String nonce, String echostr)
      throws Exception {
    if (!signature.equals(plainSha1Signature(timestamp, nonce))) {
      throw new IllegalStateException("echostr 明文签名校验失败");
    }
    return echostr;
  }

  String decryptMsg(String msgSignature, String timestamp, String nonce, String encrypt)
      throws Exception {
    if (!msgSignature.equals(sha1Signature(timestamp, nonce, encrypt))) {
      throw new IllegalStateException("回调签名校验失败");
    }
    return decrypt(encrypt);
  }

  /** 单测 / 回环：加密明文 XML。 */
  String encrypt(String plainXml) throws Exception {
    byte[] random = new byte[RANDOM_LEN];
    SECURE_RANDOM.nextBytes(random);
    byte[] xmlBytes = plainXml.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] receiveBytes = receiveId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] raw = new byte[RANDOM_LEN + LENGTH_HEADER_BYTES + xmlBytes.length + receiveBytes.length];
    System.arraycopy(random, 0, raw, 0, RANDOM_LEN);
    int xmlLen = xmlBytes.length;
    raw[RANDOM_LEN] = (byte) ((xmlLen >> 24) & 0xff);
    raw[RANDOM_LEN + 1] = (byte) ((xmlLen >> 16) & 0xff);
    raw[RANDOM_LEN + 2] = (byte) ((xmlLen >> 8) & 0xff);
    raw[RANDOM_LEN + 3] = (byte) (xmlLen & 0xff);
    System.arraycopy(xmlBytes, 0, raw, RANDOM_LEN + LENGTH_HEADER_BYTES, xmlBytes.length);
    System.arraycopy(
        receiveBytes,
        0,
        raw,
        RANDOM_LEN + LENGTH_HEADER_BYTES + xmlBytes.length,
        receiveBytes.length);
    byte[] padded = pkcs7Pad(raw);
    javax.crypto.Cipher cipherInst = javax.crypto.Cipher.getInstance(TRANSFORMATION);
    javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(aesKey, "AES");
    javax.crypto.spec.IvParameterSpec iv =
        new javax.crypto.spec.IvParameterSpec(aesKey, 0, AES_BLOCK);
    cipherInst.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, iv);
    return java.util.Base64.getEncoder().encodeToString(cipherInst.doFinal(padded));
  }

  String signature(String timestamp, String nonce, String encrypt) throws Exception {
    return sha1Signature(timestamp, nonce, encrypt);
  }

  String plainSignature(String timestamp, String nonce) throws Exception {
    return plainSha1Signature(timestamp, nonce);
  }

  private String decrypt(String encryptB64) throws Exception {
    byte[] cipher = java.util.Base64.getDecoder().decode(encryptB64);
    javax.crypto.Cipher cipherInst = javax.crypto.Cipher.getInstance(TRANSFORMATION);
    javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(aesKey, "AES");
    javax.crypto.spec.IvParameterSpec iv =
        new javax.crypto.spec.IvParameterSpec(aesKey, 0, AES_BLOCK);
    cipherInst.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, iv);
    byte[] original = pkcs7Unpad(cipherInst.doFinal(cipher));
    if (original.length < RANDOM_LEN + LENGTH_HEADER_BYTES) {
      throw new IllegalStateException("解密报文过短");
    }
    int xmlLen =
        ((original[RANDOM_LEN] & 0xff) << 24)
            | ((original[RANDOM_LEN + 1] & 0xff) << 16)
            | ((original[RANDOM_LEN + 2] & 0xff) << 8)
            | (original[RANDOM_LEN + 3] & 0xff);
    int xmlStart = RANDOM_LEN + LENGTH_HEADER_BYTES;
    int xmlEnd = xmlStart + xmlLen;
    if (xmlLen < 0 || xmlEnd > original.length) {
      throw new IllegalStateException("解密报文长度非法");
    }
    String xml = new String(original, xmlStart, xmlLen, java.nio.charset.StandardCharsets.UTF_8);
    String fromReceiveId =
        new String(
            original, xmlEnd, original.length - xmlEnd, java.nio.charset.StandardCharsets.UTF_8);
    if (!receiveId.equals(fromReceiveId)) {
      throw new IllegalStateException(
          "receiveId 不匹配（期望=" + receiveId + " 实际=" + fromReceiveId + "）");
    }
    return xml;
  }

  /** POST 安全模式：SHA1(sorted token,timestamp,nonce,encrypt)。 */
  private String sha1Signature(String timestamp, String nonce, String encrypt) throws Exception {
    String[] arr = {token, timestamp, nonce, encrypt};
    java.util.Arrays.sort(arr);
    String raw = arr[0] + arr[1] + arr[2] + arr[3];
    return sha1Hex(raw);
  }

  /** GET 明文验签：SHA1(sorted token,timestamp,nonce)。 */
  private String plainSha1Signature(String timestamp, String nonce) throws Exception {
    String[] arr = {token, timestamp, nonce};
    java.util.Arrays.sort(arr);
    String raw = arr[0] + arr[1] + arr[2];
    return sha1Hex(raw);
  }

  private static String sha1Hex(String raw) throws Exception {
    java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
    byte[] digest = md.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    StringBuilder sb = new StringBuilder(digest.length * 2);
    for (byte b : digest) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }

  private static String padBase64(String keyB64) {
    int rem = keyB64.length() % BASE64_PAD_MOD;
    if (rem == 0) {
      return keyB64;
    }
    StringBuilder sb = new StringBuilder(keyB64);
    for (int i = 0; i < BASE64_PAD_MOD - rem; i++) {
      sb.append(BASE64_PAD);
    }
    return sb.toString();
  }

  private static byte[] pkcs7Pad(byte[] data) {
    int pad = PKCS7_BLOCK - (data.length % PKCS7_BLOCK);
    if (pad == 0) {
      pad = PKCS7_BLOCK;
    }
    byte[] out = new byte[data.length + pad];
    System.arraycopy(data, 0, out, 0, data.length);
    for (int i = data.length; i < out.length; i++) {
      out[i] = (byte) pad;
    }
    return out;
  }

  private static byte[] pkcs7Unpad(byte[] decrypted) {
    if (decrypted.length == 0) {
      throw new IllegalStateException("解密报文为空");
    }
    int pad = decrypted[decrypted.length - 1] & 0xff;
    if (pad < 1 || pad > PKCS7_BLOCK || pad > decrypted.length) {
      throw new IllegalStateException("PKCS7 填充非法: " + pad);
    }
    for (int i = 1; i <= pad; i++) {
      if ((decrypted[decrypted.length - i] & 0xff) != pad) {
        throw new IllegalStateException("PKCS7 填充校验失败");
      }
    }
    byte[] out = new byte[decrypted.length - pad];
    System.arraycopy(decrypted, 0, out, 0, out.length);
    return out;
  }
}
