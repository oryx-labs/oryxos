package io.oryxos.channel.whatsapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageServiceContractTestBase;
import java.util.List;

class WhatsAppChannelContractTest extends InboundMessageServiceContractTestBase {

  private final WhatsAppEventNormalizer normalizer = new WhatsAppEventNormalizer("contract-chan");
  private final ObjectMapper mapper = new ObjectMapper();

  @Override
  protected String channelType() {
    return "whatsapp";
  }

  @Override
  protected InboundMessage p2pMessage(String messageId, String content) {
    return normalizer.normalize(payload(messageId, "text", content, null)).get(0);
  }

  @Override
  protected InboundMessage groupMessage(String messageId, String content) {
    return new InboundMessage(
        channelType(),
        "contract-chan",
        messageId,
        ChatKind.GROUP,
        "user-1",
        "g1",
        content,
        true,
        true,
        List.of());
  }

  @Override
  protected InboundMessage nonTextualMessage(String messageId) {
    return normalizer.normalize(payload(messageId, "sticker", null, null)).get(0);
  }

  @Override
  protected InboundMessage imageMessage(String messageId) {
    return normalizer.normalize(payload(messageId, "image", null, "img-1")).get(0);
  }

  private ObjectNode payload(String messageId, String type, String text, String mediaId) {
    ObjectNode root = mapper.createObjectNode();
    ObjectNode message =
        root.putArray("entry")
            .addObject()
            .putArray("changes")
            .addObject()
            .putObject("value")
            .putArray("messages")
            .addObject();
    message.put("from", "user-1");
    message.put("id", messageId);
    message.put("timestamp", "1700000000");
    message.put("type", type);
    if (text != null) {
      message.putObject("text").put("body", text);
    }
    if (mediaId != null) {
      message.putObject(type).put("id", mediaId);
    }
    return root;
  }
}
