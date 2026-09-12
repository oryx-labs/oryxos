package io.oryxos.channel.alipay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.channel.InboundMessage;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AlipayEventNormalizerTest {

  private final AlipayEventNormalizer normalizer =
      new AlipayEventNormalizer("ops-alipay", "2014072300007148");

  @Test
  @DisplayName("文本消息 → P2P chatId")
  void text() {
    String xml =
        "<?xml version=\"1.0\" encoding=\"gbk\"?><XML>"
            + "<AppId><![CDATA[2014072300007148]]></AppId>"
            + "<FromUserId><![CDATA[2088102122458832]]></FromUserId>"
            + "<CreateTime><![CDATA[1403129848]]></CreateTime>"
            + "<MsgType><![CDATA[text]]></MsgType>"
            + "<Text><![CDATA[你好支付宝]]></Text>"
            + "<MsgId><![CDATA[msg-1]]></MsgId>"
            + "</XML>";
    Optional<InboundMessage> msg = normalizer.normalize(xml);
    assertTrue(msg.isPresent());
    assertEquals("你好支付宝", msg.get().content());
    assertEquals("2088102122458832", msg.get().userId());
    assertEquals("alipay:2014072300007148:user:2088102122458832", msg.get().chatId());
    assertEquals("msg-1", msg.get().messageId());
  }

  @Test
  @DisplayName("非文本丢弃")
  void nonText() {
    String xml =
        "<XML><MsgType><![CDATA[image]]></MsgType>"
            + "<FromUserId><![CDATA[u1]]></FromUserId></XML>";
    assertFalse(normalizer.normalize(xml).isPresent());
  }
}
