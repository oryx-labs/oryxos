package io.oryxos.channel.weixinmini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.channel.InboundMessage;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WeixinMiniEventNormalizerTest {

  private static final String APP_ID = "wx5823bf96d3bd56c7";
  private final WeixinMiniEventNormalizer normalizer =
      new WeixinMiniEventNormalizer("ops-mini", APP_ID);

  @Test
  @DisplayName("文本 XML → InboundMessage")
  void textMessage() {
    String xml =
        "<xml>"
            + "<ToUserName><![CDATA[gh_xxx]]></ToUserName>"
            + "<FromUserName><![CDATA[OPENID1]]></FromUserName>"
            + "<CreateTime>1348831860</CreateTime>"
            + "<MsgType><![CDATA[text]]></MsgType>"
            + "<Content><![CDATA[this is a test]]></Content>"
            + "<MsgId>1234567890123456</MsgId>"
            + "</xml>";
    Optional<InboundMessage> msg = normalizer.normalize(xml);
    assertTrue(msg.isPresent());
    assertEquals("this is a test", msg.get().content());
    assertTrue(msg.get().textual());
    assertEquals("1234567890123456", msg.get().messageId());
    assertEquals("mini:" + APP_ID + ":user:OPENID1", msg.get().chatId());
    assertEquals("OPENID1", msg.get().userId());
  }

  @Test
  @DisplayName("缺 MsgId 时用 FromUserName+CreateTime")
  void fallbackMessageId() {
    String xml =
        "<xml>"
            + "<FromUserName><![CDATA[OPENID2]]></FromUserName>"
            + "<CreateTime>1348831860</CreateTime>"
            + "<MsgType><![CDATA[text]]></MsgType>"
            + "<Content><![CDATA[hi]]></Content>"
            + "</xml>";
    Optional<InboundMessage> msg = normalizer.normalize(xml);
    assertTrue(msg.isPresent());
    assertEquals("OPENID2:1348831860", msg.get().messageId());
  }

  @Test
  @DisplayName("事件/非文本丢弃")
  void ignoreNonText() {
    String eventXml =
        "<xml>"
            + "<FromUserName><![CDATA[OPENID3]]></FromUserName>"
            + "<MsgType><![CDATA[event]]></MsgType>"
            + "<Event><![CDATA[user_enter_tempsession]]></Event>"
            + "</xml>";
    assertTrue(normalizer.normalize(eventXml).isEmpty());

    String imageXml =
        "<xml>"
            + "<FromUserName><![CDATA[OPENID4]]></FromUserName>"
            + "<MsgType><![CDATA[image]]></MsgType>"
            + "<PicUrl><![CDATA[http://example.com/a.jpg]]></PicUrl>"
            + "</xml>";
    assertTrue(normalizer.normalize(imageXml).isEmpty());
  }

  @Test
  @DisplayName("空内容丢弃")
  void ignoreBlankContent() {
    String xml =
        "<xml>"
            + "<FromUserName><![CDATA[OPENID5]]></FromUserName>"
            + "<MsgType><![CDATA[text]]></MsgType>"
            + "<Content><![CDATA[   ]]></Content>"
            + "<MsgId>99</MsgId>"
            + "</xml>";
    assertTrue(normalizer.normalize(xml).isEmpty());
  }
}
