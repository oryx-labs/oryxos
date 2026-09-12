package io.oryxos.channel.weixinmp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WeixinMpMsgCryptTest {

  private static final String TOKEN = "QDG6eK";
  private static final String APP_ID = "wx5823bf96d3bd56c7";
  // 32 字节全 0 的 EncodingAESKey（去 padding，43 字符）
  private static final String AES_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

  @Test
  @DisplayName("encrypt/decrypt 回环 + 签名")
  void roundTrip() throws Exception {
    WeixinMpMsgCrypt crypt = new WeixinMpMsgCrypt(TOKEN, AES_KEY, APP_ID);
    String xml =
        "<xml><ToUserName><![CDATA["
            + APP_ID
            + "]]></ToUserName><MsgType><![CDATA[text]]></MsgType>"
            + "<FromUserName><![CDATA[OPENID]]></FromUserName>"
            + "<Content><![CDATA[hello]]></Content></xml>";
    String cipher = crypt.encrypt(xml);
    String ts = "1409659813";
    String nonce = "1372623149";
    String sig = crypt.signature(ts, nonce, cipher);
    String plain = crypt.decryptMsg(sig, ts, nonce, cipher);
    assertTrue(plain.contains("text"));
    assertTrue(plain.contains("OPENID"));
    assertEquals(xml, plain);
  }

  @Test
  @DisplayName("长明文（PKCS7 填充>16）仍能回环")
  void longPlaintextRoundTrip() throws Exception {
    WeixinMpMsgCrypt crypt = new WeixinMpMsgCrypt(TOKEN, AES_KEY, APP_ID);
    StringBuilder sb =
        new StringBuilder("<xml><MsgType><![CDATA[text]]></MsgType><Content><![CDATA[");
    for (int i = 0; i < 200; i++) {
      sb.append('A');
    }
    sb.append("]]></Content><FromUserName><![CDATA[OPENID]]></FromUserName></xml>");
    String xml = sb.toString();
    String cipher = crypt.encrypt(xml);
    String plain = crypt.decryptMsg(crypt.signature("1", "2", cipher), "1", "2", cipher);
    assertEquals(xml, plain);
  }

  @Test
  @DisplayName("签名错误拒绝")
  void badSignature() throws Exception {
    WeixinMpMsgCrypt crypt = new WeixinMpMsgCrypt(TOKEN, AES_KEY, APP_ID);
    String cipher = crypt.encrypt("<xml>hi</xml>");
    assertThrows(IllegalStateException.class, () -> crypt.decryptMsg("deadbeef", "1", "2", cipher));
  }
}
