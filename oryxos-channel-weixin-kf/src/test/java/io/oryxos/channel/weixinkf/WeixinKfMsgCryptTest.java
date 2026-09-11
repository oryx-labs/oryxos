package io.oryxos.channel.weixinkf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WeixinKfMsgCryptTest {

  private static final String TOKEN = "QDG6eK";
  private static final String CORP_ID = "wx5823bf96d3bd56c7";
  // 32 字节全 0 的 EncodingAESKey（去 padding，43 字符）
  private static final String AES_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

  @Test
  @DisplayName("encrypt/decrypt 回环 + 签名")
  void roundTrip() throws Exception {
    WeixinKfMsgCrypt crypt = new WeixinKfMsgCrypt(TOKEN, AES_KEY, CORP_ID);
    String xml =
        "<xml><ToUserName><![CDATA["
            + CORP_ID
            + "]]></ToUserName><Event><![CDATA[kf_msg_or_event]]></Event>"
            + "<Token><![CDATA[ENCABCDEF]]></Token>"
            + "<OpenKfId><![CDATA[wkOPEN]]></OpenKfId></xml>";
    String cipher = crypt.encrypt(xml);
    String ts = "1409659813";
    String nonce = "1372623149";
    String sig = crypt.signature(ts, nonce, cipher);
    String plain = crypt.decryptMsg(sig, ts, nonce, cipher);
    assertTrue(plain.contains("kf_msg_or_event"));
    assertTrue(plain.contains("ENCABCDEF"));
    assertEquals(xml, plain);
  }

  @Test
  @DisplayName("签名错误拒绝")
  void badSignature() throws Exception {
    WeixinKfMsgCrypt crypt = new WeixinKfMsgCrypt(TOKEN, AES_KEY, CORP_ID);
    String cipher = crypt.encrypt("<xml>hi</xml>");
    assertThrows(IllegalStateException.class, () -> crypt.decryptMsg("deadbeef", "1", "2", cipher));
  }
}
