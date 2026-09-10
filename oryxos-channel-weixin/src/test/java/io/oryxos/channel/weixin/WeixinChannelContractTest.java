package io.oryxos.channel.weixin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageServiceContractTestBase;
import java.util.List;

class WeixinChannelContractTest extends InboundMessageServiceContractTestBase {

  private final WeixinEventNormalizer normalizer =
      new WeixinEventNormalizer("contract-chan", "bot-1");
  private final ObjectMapper mapper = new ObjectMapper();

  @Override
  protected String channelType() {
    return "weixin";
  }

  @Override
  protected InboundMessage p2pMessage(String messageId, String content) {
    return normalizer.normalize(payload(messageId, content, false)).orElseThrow();
  }

  @Override
  protected InboundMessage groupMessage(String messageId, String content) {
    return new InboundMessage(
        channelType(),
        "contract-chan",
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
    // B7：无附件的非文本（如未识别卡片）；有 CDN 媒体走 B7b
    return new InboundMessage(
        channelType(),
        "contract-chan",
        messageId,
        ChatKind.P2P,
        "user-1",
        WeixinChatTargets.user("user-1"),
        "",
        false,
        false,
        List.of());
  }

  @Override
  protected InboundMessage imageMessage(String messageId) {
    return new InboundMessage(
        channelType(),
        "contract-chan",
        messageId,
        ChatKind.P2P,
        "user-1",
        WeixinChatTargets.user("user-1"),
        "",
        false,
        false,
        List.of(InboundAttachment.imageUrl("https://example.com/a.jpg")));
  }

  private ObjectNode payload(String messageId, String text, boolean group) {
    ObjectNode root = mapper.createObjectNode();
    root.put("from_user_id", "user-1");
    root.put("message_id", messageId);
    root.put("context_token", "ctx");
    if (group) {
      root.put("room_id", "r1");
    }
    ArrayNode items = root.putArray("item_list");
    ObjectNode item = items.addObject();
    item.put("type", 1);
    item.putObject("text_item").put("text", text);
    return root;
  }
}
