package io.oryxos.channel.weixinmp;

import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageServiceContractTestBase;
import java.util.List;

class WeixinMpChannelContractTest extends InboundMessageServiceContractTestBase {

  private static final String APP_ID = "wx5823bf96d3bd56c7";
  private final WeixinMpEventNormalizer normalizer =
      new WeixinMpEventNormalizer("contract-mp", APP_ID);

  @Override
  protected String channelType() {
    return "weixin_mp";
  }

  @Override
  protected InboundMessage p2pMessage(String messageId, String content) {
    String xml =
        "<xml>"
            + "<FromUserName><![CDATA[OPENID1]]></FromUserName>"
            + "<MsgType><![CDATA[text]]></MsgType>"
            + "<Content><![CDATA["
            + content
            + "]]></Content>"
            + "<MsgId>"
            + messageId
            + "</MsgId>"
            + "</xml>";
    return normalizer.normalize(xml).orElseThrow();
  }

  @Override
  protected InboundMessage groupMessage(String messageId, String content) {
    return new InboundMessage(
        channelType(),
        "contract-mp",
        messageId,
        ChatKind.GROUP,
        "user-1",
        "group:g1",
        content,
        true,
        true,
        List.of());
  }

  @Override
  protected InboundMessage nonTextualMessage(String messageId) {
    return new InboundMessage(
        channelType(),
        "contract-mp",
        messageId,
        ChatKind.P2P,
        "OPENID1",
        WeixinMpChatTargets.chatId(APP_ID, "OPENID1"),
        "",
        false,
        false,
        List.of());
  }

  @Override
  protected InboundMessage imageMessage(String messageId) {
    return new InboundMessage(
        channelType(),
        "contract-mp",
        messageId,
        ChatKind.P2P,
        "OPENID1",
        WeixinMpChatTargets.chatId(APP_ID, "OPENID1"),
        "",
        false,
        false,
        List.of(io.oryxos.core.channel.InboundAttachment.imageUrl("https://example.com/a.jpg")));
  }
}
