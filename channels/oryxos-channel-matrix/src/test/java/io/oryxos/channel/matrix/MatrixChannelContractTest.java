package io.oryxos.channel.matrix;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageServiceContractTestBase;

class MatrixChannelContractTest extends InboundMessageServiceContractTestBase {

  private static final String BOT = "@oryx:hs";
  private final MatrixEventNormalizer normalizer = new MatrixEventNormalizer("contract-chan", BOT);
  private final ObjectMapper mapper = new ObjectMapper();

  @Override
  protected String channelType() {
    return "matrix";
  }

  @Override
  protected InboundMessage p2pMessage(String messageId, String content) {
    return normalizer.normalize("!dm:hs", event(messageId, content, false), true).orElseThrow();
  }

  @Override
  protected InboundMessage groupMessage(String messageId, String content) {
    return normalizer
        .normalize("!room:hs", event(messageId, BOT + " " + content, true), false)
        .orElseThrow();
  }

  @Override
  protected InboundMessage nonTextualMessage(String messageId) {
    return normalizer.normalize("!dm:hs", event(messageId, "", false), true).orElseThrow();
  }

  @Override
  protected InboundMessage imageMessage(String messageId) {
    ObjectNode event = event(messageId, "", false);
    ((ObjectNode) event.get("content")).put("msgtype", "m.image").put("url", "mxc://hs/img");
    return normalizer.normalize("!dm:hs", event, true).orElseThrow();
  }

  private ObjectNode event(String eventId, String body, boolean mention) {
    ObjectNode root = mapper.createObjectNode();
    root.put("type", "m.room.message");
    root.put("sender", "@alice:hs");
    root.put("event_id", eventId);
    ObjectNode content = root.putObject("content");
    content.put("msgtype", "m.text");
    content.put("body", body);
    if (mention) {
      content.putObject("m.mentions").putArray("user_ids").add(BOT);
    }
    return root;
  }
}
