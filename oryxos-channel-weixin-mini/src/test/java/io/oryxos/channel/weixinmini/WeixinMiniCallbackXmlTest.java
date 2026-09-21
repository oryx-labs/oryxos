package io.oryxos.channel.weixinmini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.channel.InboundMessage;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link WeixinMiniCallbackXml#cdataOrText} 回归：空 CDATA 段不得返回 `<![CDATA[]]>` 残骸（issue #615）。 */
class WeixinMiniCallbackXmlTest {

  @Test
  @DisplayName("正常 CDATA 段提取内容")
  void normalCdata() {
    assertEquals(
        "msg-1", WeixinMiniCallbackXml.cdataOrText("<MsgId><![CDATA[msg-1]]></MsgId>", "MsgId"));
  }

  @Test
  @DisplayName("空 CDATA 段返回空白而非包装残骸")
  void emptyCdata() {
    String value = WeixinMiniCallbackXml.cdataOrText("<MsgId><![CDATA[]]></MsgId>", "MsgId");
    assertTrue(value == null || value.isBlank(), "空 CDATA 应返回空串/null，实际: " + value);
  }

  @Test
  @DisplayName("普通文本与空普通文本")
  void plainText() {
    assertEquals("abc", WeixinMiniCallbackXml.cdataOrText("<MsgId>abc</MsgId>", "MsgId"));
    assertNull(WeixinMiniCallbackXml.cdataOrText("<MsgId></MsgId>", "MsgId"));
  }

  @Test
  @DisplayName("标签缺失返回 null")
  void missingTag() {
    assertNull(WeixinMiniCallbackXml.cdataOrText("<XML></XML>", "MsgId"));
  }

  @Test
  @DisplayName("空 CDATA 的 MsgId 回退 fromUser:CreateTime，不产生常量垃圾 ID")
  void emptyCdataMsgIdFallsBack() {
    String xml =
        "<XML><FromUserName><![CDATA[u1]]></FromUserName>"
            + "<CreateTime><![CDATA[1700000000]]></CreateTime>"
            + "<MsgType><![CDATA[text]]></MsgType>"
            + "<Content><![CDATA[你好]]></Content>"
            + "<MsgId><![CDATA[]]></MsgId></XML>";
    Optional<InboundMessage> msg =
        new WeixinMiniEventNormalizer("ops-mini", "app-1").normalize(xml);
    assertTrue(msg.isPresent());
    assertEquals("u1:1700000000", msg.get().messageId());
  }
}
