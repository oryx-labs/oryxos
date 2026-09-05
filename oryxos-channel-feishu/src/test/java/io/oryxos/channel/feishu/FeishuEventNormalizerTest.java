package io.oryxos.channel.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.MentionEvent;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1Data;
import com.lark.oapi.service.im.v1.model.UserId;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundMessage;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 017 T011/T016：飞书事件归一化——文本/非文本、群聊 @ 判定与剥离、非 @ 丢弃（A1/A2）。 */
class FeishuEventNormalizerTest {

  private static final String BOT_OPEN_ID = "ou_bot_self";

  private final FeishuEventNormalizer normalizer =
      new FeishuEventNormalizer("ops-feishu", BOT_OPEN_ID);

  private static P2MessageReceiveV1 event(
      String chatType, String msgType, String contentJson, MentionEvent... mentions) {
    EventMessage message = new EventMessage();
    message.setMessageId("om_msg_1");
    message.setChatId("oc_chat_1");
    message.setChatType(chatType);
    message.setMessageType(msgType);
    message.setContent(contentJson);
    if (mentions.length > 0) {
      message.setMentions(mentions);
    }
    UserId senderId = new UserId();
    senderId.setOpenId("ou_sender_1");
    EventSender sender = new EventSender();
    sender.setSenderId(senderId);
    P2MessageReceiveV1Data data = new P2MessageReceiveV1Data();
    data.setMessage(message);
    data.setSender(sender);
    P2MessageReceiveV1 event = new P2MessageReceiveV1();
    event.setEvent(data);
    return event;
  }

  private static MentionEvent mention(String key, String openId, String type, String name) {
    MentionEvent m = new MentionEvent();
    m.setKey(key);
    UserId id = new UserId();
    id.setOpenId(openId);
    m.setId(id);
    m.setMentionedType(type);
    m.setName(name);
    return m;
  }

  @Test
  @DisplayName("私聊文本：归一化为 P2P 文本消息，字段齐全")
  void p2pText() {
    Optional<InboundMessage> out =
        normalizer.normalize(event("p2p", "text", "{\"text\":\"磁盘告警怎么处理？\"}"));
    assertTrue(out.isPresent());
    InboundMessage msg = out.get();
    assertEquals("feishu", msg.channelType());
    assertEquals("ops-feishu", msg.channelName());
    assertEquals("om_msg_1", msg.messageId());
    assertEquals(ChatKind.P2P, msg.chatKind());
    assertEquals("ou_sender_1", msg.userId());
    assertEquals("oc_chat_1", msg.chatId());
    assertEquals("磁盘告警怎么处理？", msg.content());
    assertTrue(msg.textual());
  }

  @Test
  @DisplayName("私聊图片：textual=false，附带 image_key 附件")
  void p2pImage() {
    Optional<InboundMessage> out =
        normalizer.normalize(event("p2p", "image", "{\"image_key\":\"img_x\"}"));
    assertTrue(out.isPresent());
    assertFalse(out.get().textual());
    assertEquals("", out.get().content());
    assertEquals(1, out.get().attachments().size());
    assertEquals("img_x", out.get().attachments().get(0).reference());
  }

  @Test
  @DisplayName("私聊文件：textual=false，附带 file_key 附件")
  void p2pFile() {
    Optional<InboundMessage> out =
        normalizer.normalize(
            event("p2p", "file", "{\"file_key\":\"file_x\",\"file_name\":\"a.pdf\"}"));
    assertTrue(out.isPresent());
    assertFalse(out.get().textual());
    assertTrue(out.get().processable());
    assertEquals(1, out.get().attachments().size());
    assertEquals("file", out.get().attachments().get(0).type());
    assertEquals("file_x", out.get().attachments().get(0).reference());
  }

  @Test
  @DisplayName("群聊 @ 本机器人：占位符剥离，归一化为 GROUP 且 mentionedBot=true")
  void groupAtBotStripsPlaceholder() {
    Optional<InboundMessage> out =
        normalizer.normalize(
            event(
                "group",
                "text",
                "{\"text\":\"@_user_1 昨晚的发布为什么回滚了？\"}",
                mention("@_user_1", BOT_OPEN_ID, "bot", "运维小欧")));
    assertTrue(out.isPresent());
    assertEquals(ChatKind.GROUP, out.get().chatKind());
    assertTrue(out.get().mentionedBot());
    assertEquals("昨晚的发布为什么回滚了？", out.get().content());
  }

  @Test
  @DisplayName("群聊非 @ 本机器人：丢弃（返回 empty，不落任何记录，SC-002）")
  void groupWithoutBotMentionDropped() {
    assertTrue(normalizer.normalize(event("group", "text", "{\"text\":\"大家午饭吃什么\"}")).isEmpty());
    // @ 了别人但没 @ 机器人，同样丢弃
    assertTrue(
        normalizer
            .normalize(
                event(
                    "group",
                    "text",
                    "{\"text\":\"@_user_1 看一下\"}",
                    mention("@_user_1", "ou_other_user", "user", "张三")))
            .isEmpty());
  }

  @Test
  @DisplayName("群聊 @ 机器人 + @ 他人混合：机器人占位符剥离、他人替换为人名")
  void groupMixedMentions() {
    Optional<InboundMessage> out =
        normalizer.normalize(
            event(
                "group",
                "text",
                "{\"text\":\"@_user_1 请把结论同步给 @_user_2 谢谢\"}",
                mention("@_user_1", BOT_OPEN_ID, "bot", "运维小欧"),
                mention("@_user_2", "ou_zhang", "user", "张三")));
    assertTrue(out.isPresent());
    assertEquals("请把结论同步给 @张三 谢谢", out.get().content());
  }

  @Test
  @DisplayName("bot open_id 缺失时降级：mentioned_type=bot 即视为 @ 本机器人")
  void degradedBotJudgementByType() {
    FeishuEventNormalizer degraded = new FeishuEventNormalizer("ops-feishu", null);
    Optional<InboundMessage> out =
        degraded.normalize(
            event(
                "group",
                "text",
                "{\"text\":\"@_user_1 在吗\"}",
                mention("@_user_1", "ou_whatever", "bot", "运维小欧")));
    assertTrue(out.isPresent());
    assertTrue(out.get().mentionedBot());
  }

  @Test
  @DisplayName("content 非法 JSON / 空文本：按空文本处理不抛异常")
  void malformedContent() {
    assertEquals("", FeishuEventNormalizer.extractText("not-json"));
    assertEquals("", FeishuEventNormalizer.extractText(null));
    Optional<InboundMessage> out = normalizer.normalize(event("p2p", "text", "not-json{{"));
    assertTrue(out.isPresent());
    assertEquals("", out.get().content());
  }

  @Test
  @DisplayName("缺关键字段（sender/message）：丢弃")
  void missingFieldsDropped() {
    assertTrue(normalizer.normalize(null).isEmpty());
    P2MessageReceiveV1 noData = new P2MessageReceiveV1();
    assertTrue(normalizer.normalize(noData).isEmpty());
    P2MessageReceiveV1 e = event("p2p", "text", "{\"text\":\"hi\"}");
    e.getEvent().setSender(null);
    assertTrue(normalizer.normalize(e).isEmpty());
  }
}
