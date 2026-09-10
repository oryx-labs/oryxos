package io.oryxos.channel.weixinkf;

/**
 * 企微回调同族加解密（WXBizMsgCrypt）：Token + EncodingAESKey + receiveId(CorpId)。
 *
 * <p>协议固定 AES-256-CBC；与媒体临时文件解密不同。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "CIPHER_INTEGRITY",
    justification =
        "企微/微信客服回调协议固定 AES-256-CBC（官方 WXBizMsgCrypt），无法改用 AEAD；" + "完整性依赖 msg_signature（SHA1）校验。")
final class WeixinKfMsgCrypt {

  private static final int AES_BLOCK = 16;
  private static final int RANDOM_LEN = 16;
  private static final String TRANSFORMATION = "AES/CBC/NoPadding";

  private final byte[] aesKey;
  private final String token;
  private final String receiveId;

  WeixinKfMsgCrypt(String token, String encodingAesKey, String receiveId) {
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("callback token 为空");
    }
    if (encodingAesKey == null || encodingAesKey.isBlank()) {
      throw new IllegalArgumentException("encoding_aes_key 为空");
    }
    if (receiveId == null || receiveId.isBlank()) {
      throw new IllegalArgumentException("corpid/receiveId 为空");
    }
    String keyB64 = encodingAesKey.strip();
    while (keyB64.length() % 4 != 0) {
      keyB64 = keyB64 + "=";
    }
    byte[] key = java.util.Base64.getDecoder().decode(keyB64);
    if (key.length != 32) {
      throw new IllegalArgumentException("encoding_aes_key 解码后须为 32 字节");
    }
    this.aesKey = key;
    this.token = token.strip();
    this.receiveId = receiveId.strip();
  }

  String verifyUrl(String msgSignature, String timestamp, String nonce, String echostr)
      throws Exception {
    if (!msgSignature.equals(sha1Signature(timestamp, nonce, echostr))) {
      throw new IllegalStateException("echostr 签名校验失败");
    }
    return decrypt(echostr);
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
    new java.security.SecureRandom().nextBytes(random);
    byte[] xmlBytes = plainXml.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] receiveBytes = receiveId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] raw = new byte[RANDOM_LEN + 4 + xmlBytes.length + receiveBytes.length];
    System.arraycopy(random, 0, raw, 0, RANDOM_LEN);
    int xmlLen = xmlBytes.length;
    raw[RANDOM_LEN] = (byte) ((xmlLen >> 24) & 0xff);
    raw[RANDOM_LEN + 1] = (byte) ((xmlLen >> 16) & 0xff);
    raw[RANDOM_LEN + 2] = (byte) ((xmlLen >> 8) & 0xff);
    raw[RANDOM_LEN + 3] = (byte) (xmlLen & 0xff);
    System.arraycopy(xmlBytes, 0, raw, RANDOM_LEN + 4, xmlBytes.length);
    System.arraycopy(receiveBytes, 0, raw, RANDOM_LEN + 4 + xmlBytes.length, receiveBytes.length);
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

  private String decrypt(String encryptB64) throws Exception {
    byte[] cipher = java.util.Base64.getDecoder().decode(encryptB64);
    javax.crypto.Cipher cipherInst = javax.crypto.Cipher.getInstance(TRANSFORMATION);
    javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(aesKey, "AES");
    javax.crypto.spec.IvParameterSpec iv =
        new javax.crypto.spec.IvParameterSpec(aesKey, 0, AES_BLOCK);
    cipherInst.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, iv);
    byte[] original = pkcs7Unpad(cipherInst.doFinal(cipher));
    if (original.length < RANDOM_LEN + 4) {
      throw new IllegalStateException("解密报文过短");
    }
    int xmlLen =
        ((original[RANDOM_LEN] & 0xff) << 24)
            | ((original[RANDOM_LEN + 1] & 0xff) << 16)
            | ((original[RANDOM_LEN + 2] & 0xff) << 8)
            | (original[RANDOM_LEN + 3] & 0xff);
    int xmlStart = RANDOM_LEN + 4;
    int xmlEnd = xmlStart + xmlLen;
    if (xmlLen < 0 || xmlEnd > original.length) {
      throw new IllegalStateException("解密报文长度非法");
    }
    String xml = new String(original, xmlStart, xmlLen, java.nio.charset.StandardCharsets.UTF_8);
    String fromReceiveId =
        new String(
            original, xmlEnd, original.length - xmlEnd, java.nio.charset.StandardCharsets.UTF_8);
    if (!receiveId.equals(fromReceiveId)) {
      throw new IllegalStateException("receiveId 不匹配");
    }
    return xml;
  }

  private String sha1Signature(String timestamp, String nonce, String encrypt) throws Exception {
    String[] arr = {token, timestamp, nonce, encrypt};
    java.util.Arrays.sort(arr);
    String raw = arr[0] + arr[1] + arr[2] + arr[3];
    java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
    byte[] digest = md.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    StringBuilder sb = new StringBuilder(digest.length * 2);
    for (byte b : digest) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }

  private static byte[] pkcs7Pad(byte[] data) {
    int pad = AES_BLOCK - (data.length % AES_BLOCK);
    if (pad == 0) {
      pad = AES_BLOCK;
    }
    byte[] out = new byte[data.length + pad];
    System.arraycopy(data, 0, out, 0, data.length);
    for (int i = data.length; i < out.length; i++) {
      out[i] = (byte) pad;
    }
    return out;
  }

  private static byte[] pkcs7Unpad(byte[] decrypted) {
    int pad = decrypted[decrypted.length - 1] & 0xff;
    if (pad < 1 || pad > AES_BLOCK) {
      return decrypted;
    }
    for (int i = 1; i <= pad; i++) {
      if ((decrypted[decrypted.length - i] & 0xff) != pad) {
        return decrypted;
      }
    }
    byte[] out = new byte[decrypted.length - pad];
    System.arraycopy(decrypted, 0, out, 0, out.length);
    return out;
  }
}
