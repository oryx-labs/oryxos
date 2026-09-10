package io.oryxos.channel.qq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageServiceContractTestBase;

class QqChannelContractTest extends InboundMessageServiceContractTestBase {

  private final QqEventNormalizer normalizer = new QqEventNormalizer("contract-qq");
  private final ObjectMapper mapper = new ObjectMapper();

  @Override
  protected String channelType() {
    return "qq";
  }

  @Override
  protected InboundMessage p2pMessage(String messageId, String content) {
    return normalizer.normalize(QqEventNormalizer.EVENT_C2C, c2c(messageId, content)).orElseThrow();
  }

  @Override
  protected InboundMessage groupMessage(String messageId, String content) {
    return normalizer
        .normalize(QqEventNormalizer.EVENT_GROUP_AT, group(messageId, content))
        .orElseThrow();
  }

  @Override
  protected InboundMessage nonTextualMessage(String messageId) {
    return normalizer.normalize(QqEventNormalizer.EVENT_C2C, c2c(messageId, "")).orElseThrow();
  }

  @Override
  protected InboundMessage imageMessage(String messageId) {
    ObjectNode data = mapper.createObjectNode();
    data.put("id", messageId);
    data.put("content", "");
    data.putObject("author").put("user_openid", "u1");
    data.putArray("attachments")
        .addObject()
        .put("url", "https://multimedia.nt.qq.com.cn/download?id=img")
        .put("filename", "img.jpg")
        .put("content_type", "image/jpeg")
        .put("width", 100)
        .put("height", 100);
    return normalizer.normalize(QqEventNormalizer.EVENT_C2C, data).orElseThrow();
  }

  private ObjectNode c2c(String messageId, String content) {
    ObjectNode data = mapper.createObjectNode();
    data.put("id", messageId);
    data.put("content", content);
    data.putObject("author").put("user_openid", "u1");
    return data;
  }

  private ObjectNode group(String messageId, String content) {
    ObjectNode data = mapper.createObjectNode();
    data.put("id", messageId);
    data.put("group_openid", "g1");
    data.put("content", content);
    data.putObject("author").put("member_openid", "m1");
    return data;
  }
}
