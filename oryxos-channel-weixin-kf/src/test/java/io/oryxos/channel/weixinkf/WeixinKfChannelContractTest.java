package io.oryxos.channel.weixinkf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageServiceContractTestBase;
import java.util.List;

class WeixinKfChannelContractTest extends InboundMessageServiceContractTestBase {

  private final WeixinKfEventNormalizer normalizer = new WeixinKfEventNormalizer("contract-kf");
  private final ObjectMapper mapper = new ObjectMapper();

  @Override
  protected String channelType() {
    return "weixin_kf";
  }

  @Override
  protected InboundMessage p2pMessage(String messageId, String content) {
    return normalizer.normalize(payload(messageId, "text", content)).orElseThrow();
  }

  @Override
  protected InboundMessage groupMessage(String messageId, String content) {
    return new InboundMessage(
        channelType(),
        "contract-kf",
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
    return normalizer.normalize(payload(messageId, "image", null)).orElseThrow();
  }

  @Override
  protected InboundMessage imageMessage(String messageId) {
    return new InboundMessage(
        channelType(),
        "contract-kf",
        messageId,
        ChatKind.P2P,
        "wu1",
        WeixinKfChatTargets.chatId("wk1", "wu1"),
        "",
        false,
        false,
        List.of(InboundAttachment.imageUrl("https://example.com/a.jpg")));
  }

  private ObjectNode payload(String messageId, String msgType, String text) {
    ObjectNode item = mapper.createObjectNode();
    item.put("msgid", messageId);
    item.put("open_kfid", "wk1");
    item.put("external_userid", "wu1");
    item.put("origin", 3);
    item.put("msgtype", msgType);
    if (text != null) {
      item.putObject("text").put("content", text);
    }
    return item;
  }
}
