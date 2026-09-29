package io.oryxos.channel.gchat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageServiceContractTestBase;
import java.util.List;

class GoogleChatChannelContractTest extends InboundMessageServiceContractTestBase {

  private final GoogleChatEventNormalizer normalizer =
      new GoogleChatEventNormalizer("contract-chan");
  private final ObjectMapper mapper = new ObjectMapper();

  @Override
  protected String channelType() {
    return "gchat";
  }

  @Override
  protected InboundMessage p2pMessage(String messageId, String content) {
    return normalizer.normalize(event(messageId, "DM", content, null)).orElseThrow();
  }

  @Override
  protected InboundMessage groupMessage(String messageId, String content) {
    return normalizer
        .normalize(event(messageId, "ROOM", "@Bot " + content, " " + content))
        .orElseThrow();
  }

  @Override
  protected InboundMessage nonTextualMessage(String messageId) {
    return normalizer.normalize(event(messageId, "DM", "", null)).orElseThrow();
  }

  @Override
  protected InboundMessage imageMessage(String messageId) {
    return new InboundMessage(
        channelType(),
        "contract-chan",
        messageId,
        ChatKind.P2P,
        "users/1",
        "spaces/AAA",
        "",
        false,
        false,
        List.of(InboundAttachment.imageUrl("https://example/img")));
  }

  private ObjectNode event(String messageId, String spaceType, String text, String argumentText) {
    ObjectNode root = mapper.createObjectNode();
    root.put("type", "MESSAGE");
    ObjectNode message = root.putObject("message");
    message.put("name", messageId);
    message.put("text", text);
    if (argumentText != null) {
      message.put("argumentText", argumentText);
    }
    message.putObject("sender").put("name", "users/1");
    message.putObject("space").put("name", "spaces/AAA").put("type", spaceType);
    return root;
  }
}
