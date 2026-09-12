package io.oryxos.channel.weixinmini;

import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageServiceContractTestBase;
import java.util.List;

class WeixinMiniChannelContractTest extends InboundMessageServiceContractTestBase {

  private static final String APP_ID = "wx5823bf96d3bd56c7";
  private final WeixinMiniEventNormalizer normalizer =
      new WeixinMiniEventNormalizer("contract-mini", APP_ID);

  @Override
  protected String channelType() {
    return "weixin_mini";
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
        "contract-mini",
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
        "contract-mini",
        messageId,
        ChatKind.P2P,
        "OPENID1",
        WeixinMiniChatTargets.chatId(APP_ID, "OPENID1"),
        "",
        false,
        false,
        List.of());
  }

  @Override
  protected InboundMessage imageMessage(String messageId) {
    return new InboundMessage(
        channelType(),
        "contract-mini",
        messageId,
        ChatKind.P2P,
        "OPENID1",
        WeixinMiniChatTargets.chatId(APP_ID, "OPENID1"),
        "",
        false,
        false,
        List.of(io.oryxos.core.channel.InboundAttachment.imageUrl("https://example.com/a.jpg")));
  }
}
