package io.oryxos.channel.teams;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageServiceContractTestBase;
import java.util.List;

class TeamsChannelContractTest extends InboundMessageServiceContractTestBase {

  private final TeamsEventNormalizer normalizer =
      new TeamsEventNormalizer("contract-chan", "app-1");
  private final ObjectMapper mapper = new ObjectMapper();

  @Override
  protected String channelType() {
    return "teams";
  }

  @Override
  protected InboundMessage p2pMessage(String messageId, String content) {
    return normalizer.normalize(activity(messageId, "personal", content, false)).orElseThrow();
  }

  @Override
  protected InboundMessage groupMessage(String messageId, String content) {
    return normalizer
        .normalize(activity(messageId, "channel", "<at>Bot</at> " + content, true))
        .orElseThrow();
  }

  @Override
  protected InboundMessage nonTextualMessage(String messageId) {
    return normalizer.normalize(activity(messageId, "personal", "", false)).orElseThrow();
  }

  @Override
  protected InboundMessage imageMessage(String messageId) {
    return new InboundMessage(
        channelType(),
        "contract-chan",
        messageId,
        io.oryxos.core.channel.ChatKind.P2P,
        "29:user",
        "19:chat",
        "",
        false,
        false,
        List.of(io.oryxos.core.channel.InboundAttachment.imageUrl("https://example/img")));
  }

  private ObjectNode activity(String id, String convType, String text, boolean mention) {
    ObjectNode root = mapper.createObjectNode();
    root.put("type", "message");
    root.put("id", id);
    root.put("text", text);
    root.putObject("from").put("id", "29:user");
    root.putObject("conversation").put("id", "19:chat").put("conversationType", convType);
    if (mention) {
      root.putArray("entities")
          .addObject()
          .put("type", "mention")
          .putObject("mentioned")
          .put("id", "28:app-1");
    }
    return root;
  }
}
