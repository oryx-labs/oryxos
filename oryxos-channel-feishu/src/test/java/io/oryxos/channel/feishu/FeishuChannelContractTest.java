package io.oryxos.channel.feishu;

import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.MentionEvent;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1Data;
import com.lark.oapi.service.im.v1.model.UserId;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageServiceContractTestBase;

/**
 * 契约测试集·飞书档（017 T025 / SC-007）：消息经 {@link FeishuEventNormalizer} 从真实结构的 im.message.receive_v1
 * 事件归一化产出，再跑与桩档完全相同的 B1~B10 断言——证明飞书适配器 满足入站渠道契约且契约行为与渠道实现无关。
 */
class FeishuChannelContractTest extends InboundMessageServiceContractTestBase {

  private static final String BOT_OPEN_ID = "ou_bot_self";

  private final FeishuEventNormalizer normalizer =
      new FeishuEventNormalizer("contract-chan", BOT_OPEN_ID);

  @Override
  protected String channelType() {
    return "feishu";
  }

  @Override
  protected InboundMessage p2pMessage(String messageId, String content) {
    return normalizer
        .normalize(event(messageId, "p2p", "text", "{\"text\":\"" + content + "\"}", null))
        .orElseThrow();
  }

  @Override
  protected InboundMessage groupMessage(String messageId, String content) {
    MentionEvent botMention = new MentionEvent();
    botMention.setKey("@_user_1");
    UserId id = new UserId();
    id.setOpenId(BOT_OPEN_ID);
    botMention.setId(id);
    botMention.setMentionedType("bot");
    botMention.setName("运维小欧");
    return normalizer
        .normalize(
            event(
                messageId, "group", "text", "{\"text\":\"@_user_1 " + content + "\"}", botMention))
        .orElseThrow();
  }

  @Override
  protected InboundMessage nonTextualMessage(String messageId) {
    // B7：无附件的非文本（空 media）；有 file_key 的 media 已作视频入站
    return normalizer.normalize(event(messageId, "p2p", "sticker", "{}", null)).orElseThrow();
  }

  @Override
  protected InboundMessage imageMessage(String messageId) {
    return normalizer
        .normalize(event(messageId, "p2p", "image", "{\"image_key\":\"img\"}", null))
        .orElseThrow();
  }

  private static P2MessageReceiveV1 event(
      String messageId, String chatType, String msgType, String contentJson, MentionEvent mention) {
    EventMessage message = new EventMessage();
    message.setMessageId(messageId);
    message.setChatId(chatType.equals("p2p") ? "chat-p2p" : "chat-grp");
    message.setChatType(chatType);
    message.setMessageType(msgType);
    message.setContent(contentJson);
    if (mention != null) {
      message.setMentions(new MentionEvent[] {mention});
    }
    UserId senderId = new UserId();
    senderId.setOpenId("user-1");
    EventSender sender = new EventSender();
    sender.setSenderId(senderId);
    P2MessageReceiveV1Data data = new P2MessageReceiveV1Data();
    data.setMessage(message);
    data.setSender(sender);
    P2MessageReceiveV1 event = new P2MessageReceiveV1();
    event.setEvent(data);
    return event;
  }
}
