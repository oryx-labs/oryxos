package io.oryxos.channel.wecom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageServiceContractTestBase;

/** 契约测试集·企微档：经 {@link WeComEventNormalizer} 产出归一化消息，复用 B1~B10。 */
class WeComChannelContractTest extends InboundMessageServiceContractTestBase {

  private final WeComEventNormalizer normalizer = new WeComEventNormalizer("contract-chan");
  private final ObjectMapper mapper = new ObjectMapper();

  @Override
  protected String channelType() {
    return "wecom";
  }

  @Override
  protected InboundMessage p2pMessage(String messageId, String content) {
    return normalizer.normalize(body(messageId, "single", "text", content, null)).orElseThrow();
  }

  @Override
  protected InboundMessage groupMessage(String messageId, String content) {
    return normalizer
        .normalize(body(messageId, "group", "text", "@Bot " + content, "chat-grp"))
        .orElseThrow();
  }

  @Override
  protected InboundMessage nonTextualMessage(String messageId) {
    // B7：无附件的非文本（空 location）；有 url 的 video 已作视频入站
    return normalizer.normalize(body(messageId, "single", "location", null, null)).orElseThrow();
  }

  @Override
  protected InboundMessage imageMessage(String messageId) {
    return normalizer.normalize(body(messageId, "single", "image", null, null)).orElseThrow();
  }

  private ObjectNode body(
      String msgid, String chattype, String msgtype, String text, String chatid) {
    ObjectNode body = mapper.createObjectNode();
    body.put("msgid", msgid);
    body.put("chattype", chattype);
    body.put("msgtype", msgtype);
    body.putObject("from").put("userid", "user-1");
    if (chatid != null) {
      body.put("chatid", chatid);
    }
    if ("text".equals(msgtype) && text != null) {
      body.putObject("text").put("content", text);
    } else if ("image".equals(msgtype)) {
      body.putObject("image").put("url", "https://example/img");
    }
    return body;
  }
}
