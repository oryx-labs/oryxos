package io.oryxos.channel.dingtalk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageServiceContractTestBase;

/** 契约测试集·钉钉档：经 {@link DingTalkEventNormalizer} 产出归一化消息，复用 B1~B10。 */
class DingTalkChannelContractTest extends InboundMessageServiceContractTestBase {

  private final DingTalkEventNormalizer normalizer = new DingTalkEventNormalizer("contract-chan");
  private final ObjectMapper mapper = new ObjectMapper();

  @Override
  protected String channelType() {
    return "dingtalk";
  }

  @Override
  protected InboundMessage p2pMessage(String messageId, String content) {
    return normalizer.normalize(body(messageId, "1", "text", content, false)).orElseThrow();
  }

  @Override
  protected InboundMessage groupMessage(String messageId, String content) {
    return normalizer
        .normalize(body(messageId, "2", "text", "@Bot " + content, true))
        .orElseThrow();
  }

  @Override
  protected InboundMessage nonTextualMessage(String messageId) {
    // B7：无附件的非文本（空 location）；有 downloadCode 的 video 已作视频入站
    return normalizer.normalize(body(messageId, "1", "location", null, false)).orElseThrow();
  }

  @Override
  protected InboundMessage imageMessage(String messageId) {
    return normalizer.normalize(body(messageId, "1", "picture", null, false)).orElseThrow();
  }

  private ObjectNode body(
      String msgId, String conversationType, String msgtype, String text, boolean inAtList) {
    ObjectNode body = mapper.createObjectNode();
    body.put("msgId", msgId);
    body.put("conversationType", conversationType);
    body.put("conversationId", "1".equals(conversationType) ? "conv-p2p-" + msgId : "conv-grp");
    body.put("senderId", "user-1");
    body.put("msgtype", msgtype);
    if ("2".equals(conversationType)) {
      body.put("isInAtList", inAtList);
    }
    if ("text".equals(msgtype) && text != null) {
      body.putObject("text").put("content", text);
    } else if ("picture".equals(msgtype)) {
      body.putObject("content").put("picURL", "https://example/img");
    }
    return body;
  }
}
