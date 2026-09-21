package io.oryxos.channel.weixinmp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.channel.InboundMessage;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link WeixinMpCallbackXml#cdataOrText} 回归：空 CDATA 段不得返回 `<![CDATA[]]>` 残骸（issue #615）。 */
class WeixinMpCallbackXmlTest {

  @Test
  @DisplayName("正常 CDATA 段提取内容")
  void normalCdata() {
    assertEquals(
        "msg-1", WeixinMpCallbackXml.cdataOrText("<MsgId><![CDATA[msg-1]]></MsgId>", "MsgId"));
  }

  @Test
  @DisplayName("空 CDATA 段返回空白而非包装残骸")
  void emptyCdata() {
    String value = WeixinMpCallbackXml.cdataOrText("<MsgId><![CDATA[]]></MsgId>", "MsgId");
    assertTrue(value == null || value.isBlank(), "空 CDATA 应返回空串/null，实际: " + value);
  }

  @Test
  @DisplayName("普通文本与空普通文本")
  void plainText() {
    assertEquals("abc", WeixinMpCallbackXml.cdataOrText("<MsgId>abc</MsgId>", "MsgId"));
    assertNull(WeixinMpCallbackXml.cdataOrText("<MsgId></MsgId>", "MsgId"));
  }

  @Test
  @DisplayName("标签缺失返回 null")
  void missingTag() {
    assertNull(WeixinMpCallbackXml.cdataOrText("<XML></XML>", "MsgId"));
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
    Optional<InboundMessage> msg = new WeixinMpEventNormalizer("ops-mp", "app-1").normalize(xml);
    assertTrue(msg.isPresent());
    assertEquals("u1:1700000000", msg.get().messageId());
  }
}
