package io.oryxos.channel.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageServiceContractTestBase;

class TelegramChannelContractTest extends InboundMessageServiceContractTestBase {

  private final TelegramEventNormalizer normalizer =
      new TelegramEventNormalizer("contract-chan", "OryxBot");
  private final ObjectMapper mapper = new ObjectMapper();

  @Override
  protected String channelType() {
    return "telegram";
  }

  @Override
  protected InboundMessage p2pMessage(String messageId, String content) {
    return normalizer.normalize(message("private", messageId, content, false, false)).orElseThrow();
  }

  @Override
  protected InboundMessage groupMessage(String messageId, String content) {
    return normalizer
        .normalize(message("supergroup", messageId, "@OryxBot " + content, true, false))
        .orElseThrow();
  }

  @Override
  protected InboundMessage nonTextualMessage(String messageId) {
    return normalizer.normalize(message("private", messageId, "", false, true)).orElseThrow();
  }

  @Override
  protected InboundMessage imageMessage(String messageId) {
    ObjectNode update = message("private", messageId, "", false, false);
    ((ObjectNode) update.get("message")).putArray("photo").addObject().put("file_id", "img-1");
    return normalizer.normalize(update).orElseThrow();
  }

  private ObjectNode message(
      String chatType, String messageId, String text, boolean mention, boolean sticker) {
    ObjectNode root = mapper.createObjectNode();
    root.put("update_id", 1);
    ObjectNode message = root.putObject("message");
    message.put("message_id", messageId);
    message.putObject("from").put("id", 1).put("is_bot", false);
    message.putObject("chat").put("id", chatType.equals("private") ? 1 : 99).put("type", chatType);
    if (!text.isEmpty()) {
      message.put("text", text);
    }
    if (mention) {
      message
          .putArray("entities")
          .addObject()
          .put("type", "mention")
          .put("offset", 0)
          .put("length", 8);
    }
    if (sticker) {
      message.putObject("sticker").put("file_id", "st");
    }
    return root;
  }
}
