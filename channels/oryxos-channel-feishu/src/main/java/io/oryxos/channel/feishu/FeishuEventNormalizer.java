package io.oryxos.channel.feishu;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.MentionEvent;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMessage;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 飞书 im.message.receive_v1 事件 → 归一化 {@link InboundMessage}（017 T011/T016）。
 *
 * <p>契约规则 A1/A2 在此实现：非 @ 机器人的群消息返回 {@link Optional#empty()} 丢弃（不进编排、不落记录，SC-002）； @ 机器人占位符从正文剥离、其余
 * mention 占位符替换为人名（FR-002）。
 *
 * <p>@ 本机器人判定：mention 的 open_id 与机器人自身 open_id 比对；bot open_id 不可得时（权限不足等）降级为 {@code
 * mentioned_type=="bot"} 即视为 @ 本机器人——默认权限下飞书只把 @ 本应用机器人的群消息推给本应用，降级安全。
 */
public class FeishuEventNormalizer {

  private static final Logger LOG = LoggerFactory.getLogger(FeishuEventNormalizer.class);

  private static final String CHANNEL_TYPE = "feishu";
  private static final String CHAT_TYPE_P2P = "p2p";
  private static final String MSG_TYPE_TEXT = "text";
  private static final String MSG_TYPE_IMAGE = "image";
  private static final String MSG_TYPE_FILE = "file";
  private static final String MSG_TYPE_AUDIO = "audio";
  private static final String MSG_TYPE_MEDIA = "media";
  private static final String MENTIONED_TYPE_BOT = "bot";

  private final String channelName;
  private final String botOpenId; // 可为 null（获取失败时降级判定）

  public FeishuEventNormalizer(String channelName, String botOpenId) {
    this.channelName = channelName;
    this.botOpenId = botOpenId;
  }

  /** 归一化一条接收消息事件；返回 empty 表示应当丢弃（结构不完整，或群聊未 @ 本机器人）。 */
  public Optional<InboundMessage> normalize(P2MessageReceiveV1 event) {
    if (event == null || event.getEvent() == null || event.getEvent().getMessage() == null) {
      return Optional.empty();
    }
    EventMessage message = event.getEvent().getMessage();
    String senderOpenId =
        event.getEvent().getSender() == null || event.getEvent().getSender().getSenderId() == null
            ? null
            : event.getEvent().getSender().getSenderId().getOpenId();
    if (message.getMessageId() == null || message.getChatId() == null || senderOpenId == null) {
      LOG.warn("飞书事件缺关键字段（message_id/chat_id/sender），已丢弃");
      return Optional.empty();
    }
    boolean isP2p = CHAT_TYPE_P2P.equals(message.getChatType());
    boolean mentionedBot = mentionedBot(message.getMentions());
    // A1：非 @ 机器人的群消息直接丢弃——不构造归一化对象、不落任何记录（SC-002）
    if (!isP2p && !mentionedBot) {
      return Optional.empty();
    }
    boolean textual = MSG_TYPE_TEXT.equals(message.getMessageType());
    String content =
        textual ? stripMentions(extractText(message.getContent()), message.getMentions()) : "";
    List<InboundAttachment> attachments = extractAttachments(message);
    if (!textual
        && attachments.isEmpty()
        && message.getMessageType() != null
        && !message.getMessageType().isBlank()
        && !MSG_TYPE_TEXT.equals(message.getMessageType())
        && !MSG_TYPE_IMAGE.equals(message.getMessageType())
        && !MSG_TYPE_FILE.equals(message.getMessageType())
        && !MSG_TYPE_AUDIO.equals(message.getMessageType())
        && !MSG_TYPE_MEDIA.equals(message.getMessageType())) {
      LOG.info(
          "飞书收到暂不支持的消息类型 message_type={} message_id={}",
          sanitize(message.getMessageType()),
          sanitize(message.getMessageId()));
    }
    return Optional.of(
        new InboundMessage(
            CHANNEL_TYPE,
            channelName,
            message.getMessageId(),
            isP2p ? ChatKind.P2P : ChatKind.GROUP,
            senderOpenId,
            message.getChatId(),
            content,
            textual,
            mentionedBot,
            attachments));
  }

  private List<InboundAttachment> extractAttachments(EventMessage message) {
    String msgType = message.getMessageType();
    if (MSG_TYPE_IMAGE.equals(msgType)) {
      String imageKey = extractImageKey(message.getContent());
      if (imageKey == null || imageKey.isBlank()) {
        return List.of();
      }
      return List.of(InboundAttachment.imageReference(imageKey));
    }
    if (MSG_TYPE_FILE.equals(msgType)
        || MSG_TYPE_AUDIO.equals(msgType)
        || MSG_TYPE_MEDIA.equals(msgType)) {
      String fileKey = extractFileKey(message.getContent());
      if (fileKey == null || fileKey.isBlank()) {
        return List.of();
      }
      if (MSG_TYPE_AUDIO.equals(msgType)) {
        return List.of(InboundAttachment.audioReference(fileKey));
      }
      if (MSG_TYPE_MEDIA.equals(msgType)) {
        String fileName = extractJsonStringField(message.getContent(), "file_name");
        return List.of(InboundAttachment.videoReference(fileKey, fileName));
      }
      String fileName = extractJsonStringField(message.getContent(), "file_name");
      return List.of(InboundAttachment.fileReference(fileKey, fileName));
    }
    return List.of();
  }

  /** 图片消息 content 是 JSON：{"image_key":"img_xxx"}。 */
  static String extractImageKey(String contentJson) {
    return extractJsonStringField(contentJson, "image_key");
  }

  /** 文件/语音消息 content 是 JSON：{"file_key":"file_xxx",...}。 */
  static String extractFileKey(String contentJson) {
    return extractJsonStringField(contentJson, "file_key");
  }

  private static String extractJsonStringField(String contentJson, String field) {
    if (contentJson == null || contentJson.isBlank()) {
      return null;
    }
    try {
      JsonElement root = JsonParser.parseString(contentJson);
      if (!root.isJsonObject()) {
        return null;
      }
      JsonElement key = root.getAsJsonObject().get(field);
      return key == null || key.isJsonNull() ? null : key.getAsString();
    } catch (RuntimeException e) {
      LOG.warn("飞书消息 content JSON 解析失败，按无附件字段处理");
      return null;
    }
  }

  /** mention 列表中是否 @ 了本机器人（open_id 比对；bot open_id 缺失时按 mentioned_type 降级）。 */
  private boolean mentionedBot(MentionEvent[] mentions) {
    if (mentions == null) {
      return false;
    }
    for (MentionEvent mention : mentions) {
      if (isBotMention(mention)) {
        return true;
      }
    }
    return false;
  }

  private boolean isBotMention(MentionEvent mention) {
    if (botOpenId != null) {
      return mention.getId() != null && botOpenId.equals(mention.getId().getOpenId());
    }
    return MENTIONED_TYPE_BOT.equals(mention.getMentionedType());
  }

  /** 文本消息 content 是 JSON：{"text":"@_user_1 问题内容"}。解析失败按空文本处理（不炸 handler）。 */
  static String extractText(String contentJson) {
    if (contentJson == null || contentJson.isBlank()) {
      return "";
    }
    try {
      JsonElement root = JsonParser.parseString(contentJson);
      if (!root.isJsonObject()) {
        return "";
      }
      JsonObject obj = root.getAsJsonObject();
      JsonElement text = obj.get("text");
      return text == null || text.isJsonNull() ? "" : text.getAsString();
    } catch (RuntimeException e) {
      LOG.warn("飞书文本消息 content 解析失败，按空文本处理");
      return "";
    }
  }

  /** A2：@ 本机器人的占位符（@_user_N）从正文剥离；其余 mention 占位符替换为人名。 */
  private String stripMentions(String text, MentionEvent[] mentions) {
    if (mentions == null || mentions.length == 0 || text.isEmpty()) {
      return text.strip();
    }
    String result = text;
    for (MentionEvent mention : mentions) {
      String key = mention.getKey();
      if (key == null || key.isBlank()) {
        continue;
      }
      if (isBotMention(mention)) {
        result = result.replace(key, "");
      } else {
        String name = mention.getName() == null ? "" : "@" + mention.getName();
        result = result.replace(key, name);
      }
    }
    // 剥离后可能留下多余空白，压缩为单空格
    return result.replaceAll("\\s+", " ").strip();
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
