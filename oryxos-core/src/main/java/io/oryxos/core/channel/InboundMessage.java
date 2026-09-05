package io.oryxos.core.channel;

import java.util.List;

/**
 * 归一化入站消息：平台事件经渠道适配器归一化后的唯一入站模型，编排服务只认它、不认平台原始事件（017 FR-010）。
 *
 * <p>不变式：{@code chatKind == GROUP} 的消息进入编排时必然 {@code mentionedBot == true}——非 @
 * 机器人的群消息必须在适配器归一化层丢弃（不构造本对象、不落任何记录，SC-002）。
 *
 * @param channelType 渠道类型（本期恒 "feishu"）；同时是私聊会话三元组的 channel 位
 * @param channelName channels.yaml 里的渠道条目名（一应用一条目）
 * @param messageId 平台全局消息标识，去重键（飞书官方口径：用 message_id 不用 event_id）
 * @param chatKind 私聊 / 群聊
 * @param userId 提问者标识（飞书 open_id）；私聊会话三元组的 user 位
 * @param chatId 回复目标（私聊 = 用户会话，群聊 = 群）
 * @param content 纯文本正文；群聊已剥离 @ 机器人片段、其余 mention 已替换为人名；非文本时为空串
 * @param textual 是否文本消息；false 且无附件时触发「仅支持文本」能力说明回复（FR-009）
 * @param mentionedBot 群聊是否 @ 了本机器人；进入编排的群消息恒为 true
 * @param attachments 图片/文件等媒体附件（可为空）
 */
public record InboundMessage(
    String channelType,
    String channelName,
    String messageId,
    ChatKind chatKind,
    String userId,
    String chatId,
    String content,
    boolean textual,
    boolean mentionedBot,
    List<InboundAttachment> attachments) {

  public InboundMessage {
    requireNonBlank(channelType, "channelType");
    requireNonBlank(channelName, "channelName");
    requireNonBlank(messageId, "messageId");
    if (chatKind == null) {
      throw new IllegalArgumentException("chatKind 不能为空");
    }
    requireNonBlank(userId, "userId");
    requireNonBlank(chatId, "chatId");
    if (content == null) {
      content = "";
    }
    if (attachments == null) {
      attachments = List.of();
    } else {
      attachments = List.copyOf(attachments);
    }
  }

  /** 文本或含可处理附件（如图片、文件）时进入 Agent 编排。 */
  public boolean processable() {
    return textual || !attachments.isEmpty();
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
  }
}
