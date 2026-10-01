package io.oryxos.channel.douyin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageServiceContractTestBase;
import java.util.List;

class DouyinChannelContractTest extends InboundMessageServiceContractTestBase {

  private static final String OPERATOR = "op-open";
  private final DouyinEventNormalizer normalizer =
      new DouyinEventNormalizer("contract-chan", OPERATOR);
  private final ObjectMapper mapper = new ObjectMapper();

  @Override
  protected String channelType() {
    return "douyin";
  }

  @Override
  protected InboundMessage p2pMessage(String messageId, String content) {
    return normalizer.normalize(payload(messageId, "text", content)).orElseThrow();
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
    return normalizer.normalize(payload(messageId, "image", null)).orElseThrow();
  }

  @Override
  protected InboundMessage imageMessage(String messageId) {
    return new InboundMessage(
        channelType(),
        "contract-chan",
        messageId,
        ChatKind.P2P,
        "user-1",
        DouyinChatTargets.user("user-1"),
        "",
        false,
        false,
        List.of(InboundAttachment.imageUrl("https://example.com/a.jpg")));
  }

  private ObjectNode payload(String messageId, String messageType, String text) {
    ObjectNode root = mapper.createObjectNode();
    root.put("event", DouyinEventNormalizer.EVENT_RECEIVE);
    root.put("from_user_id", "user-1");
    root.put("to_user_id", OPERATOR);
    ObjectNode content = root.putObject("content");
    content.put("conversation_short_id", "conv-1");
    content.put("server_message_id", messageId);
    content.put("conversation_type", 1);
    content.put("message_type", messageType);
    if (text != null) {
      content.put("text", text);
    }
    return root;
  }
}
