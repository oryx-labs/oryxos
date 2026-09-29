package io.oryxos.channel.weixinmini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WeixinMiniMsgCryptTest {

  private static final String TOKEN = "QDG6eK";
  private static final String APP_ID = "wx5823bf96d3bd56c7";
  private static final String AES_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

  @Test
  @DisplayName("GET 明文验签 + 原样 echostr")
  void plainUrlVerify() throws Exception {
    WeixinMiniMsgCrypt crypt = new WeixinMiniMsgCrypt(TOKEN, AES_KEY, APP_ID);
    String ts = "1409659813";
    String nonce = "1372623149";
    String echostr = "hello_echo_plain";
    String sig = crypt.plainSignature(ts, nonce);
    String returned = crypt.verifyUrlPlain(sig, ts, nonce, echostr);
    assertEquals(echostr, returned);
  }

  @Test
  @DisplayName("GET 明文验签错误拒绝")
  void plainUrlVerifyBadSig() throws Exception {
    WeixinMiniMsgCrypt crypt = new WeixinMiniMsgCrypt(TOKEN, AES_KEY, APP_ID);
    assertThrows(
        IllegalStateException.class,
        () -> crypt.verifyUrlPlain("deadbeef", "1409659813", "1372623149", "hello"));
  }

  @Test
  @DisplayName("encrypt/decrypt 回环 + msg_signature")
  void roundTrip() throws Exception {
    WeixinMiniMsgCrypt crypt = new WeixinMiniMsgCrypt(TOKEN, AES_KEY, APP_ID);
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
    WeixinMiniMsgCrypt crypt = new WeixinMiniMsgCrypt(TOKEN, AES_KEY, APP_ID);
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
  @DisplayName("msg_signature 错误拒绝")
  void badSignature() throws Exception {
    WeixinMiniMsgCrypt crypt = new WeixinMiniMsgCrypt(TOKEN, AES_KEY, APP_ID);
    String cipher = crypt.encrypt("<xml>hi</xml>");
    assertThrows(IllegalStateException.class, () -> crypt.decryptMsg("deadbeef", "1", "2", cipher));
  }
}
