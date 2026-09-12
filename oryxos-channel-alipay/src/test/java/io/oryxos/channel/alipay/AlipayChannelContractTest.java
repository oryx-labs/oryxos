package io.oryxos.channel.alipay;

import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageServiceContractTestBase;
import java.util.List;

class AlipayChannelContractTest extends InboundMessageServiceContractTestBase {

  private static final String APP_ID = "2014072300007148";
  private final AlipayEventNormalizer normalizer =
      new AlipayEventNormalizer("contract-alipay", APP_ID);

  @Override
  protected String channelType() {
    return "alipay";
  }

  @Override
  protected InboundMessage p2pMessage(String messageId, String content) {
    String xml =
        "<XML>"
            + "<FromUserId><![CDATA[2088user]]></FromUserId>"
            + "<MsgType><![CDATA[text]]></MsgType>"
            + "<Text><![CDATA["
            + content
            + "]]></Text>"
            + "<MsgId>"
            + messageId
            + "</MsgId>"
            + "</XML>";
    return normalizer.normalize(xml).orElseThrow();
  }

  @Override
  protected InboundMessage groupMessage(String messageId, String content) {
    return new InboundMessage(
        channelType(),
        "contract-alipay",
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
        "contract-alipay",
        messageId,
        ChatKind.P2P,
        "2088user",
        AlipayChatTargets.chatId(APP_ID, "2088user"),
        "",
        false,
        false,
        List.of());
  }

  @Override
  protected InboundMessage imageMessage(String messageId) {
    return new InboundMessage(
        channelType(),
        "contract-alipay",
        messageId,
        ChatKind.P2P,
        "2088user",
        AlipayChatTargets.chatId(APP_ID, "2088user"),
        "",
        false,
        false,
        List.of(io.oryxos.core.channel.InboundAttachment.imageUrl("https://example.com/a.jpg")));
  }
}
