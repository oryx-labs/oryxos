package io.oryxos.channel.weixinkf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link WeixinKfCallbackXml#cdataOrText} 回归：空 CDATA 段不得返回 `<![CDATA[]]>` 残骸（issue #615）。 */
class WeixinKfCallbackXmlTest {

  @Test
  @DisplayName("正常 CDATA 段提取内容")
  void normalCdata() {
    assertEquals(
        "enc-1",
        WeixinKfCallbackXml.cdataOrText("<Encrypt><![CDATA[enc-1]]></Encrypt>", "Encrypt"));
  }

  @Test
  @DisplayName("空 CDATA 段返回空白而非包装残骸")
  void emptyCdata() {
    String value = WeixinKfCallbackXml.cdataOrText("<Encrypt><![CDATA[]]></Encrypt>", "Encrypt");
    assertTrue(value == null || value.isBlank(), "空 CDATA 应返回空串/null，实际: " + value);
  }

  @Test
  @DisplayName("普通文本与空普通文本")
  void plainText() {
    assertEquals("abc", WeixinKfCallbackXml.cdataOrText("<Encrypt>abc</Encrypt>", "Encrypt"));
    assertNull(WeixinKfCallbackXml.cdataOrText("<Encrypt></Encrypt>", "Encrypt"));
  }

  @Test
  @DisplayName("标签缺失返回 null")
  void missingTag() {
    assertNull(WeixinKfCallbackXml.cdataOrText("<XML></XML>", "Encrypt"));
  }
}
