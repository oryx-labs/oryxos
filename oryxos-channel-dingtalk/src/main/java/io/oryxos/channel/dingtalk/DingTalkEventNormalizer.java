package io.oryxos.channel.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 钉钉 Stream 机器人回调 {@code /v1.0/im/bot/messages/get} data → 归一化 {@link InboundMessage}。
 *
 * <p>群聊：平台只推送 @ 机器人的消息，{@code isInAtList=true}；正文前导 {@code @xxx} 占位剥离。 单聊：{@code
 * conversationType=1}，回复目标为 {@code conversationId}。
 */
public class DingTalkEventNormalizer {

  private static final Logger LOG = LoggerFactory.getLogger(DingTalkEventNormalizer.class);

  static final String CHANNEL_TYPE = "dingtalk";
  private static final String CONVERSATION_SINGLE = "1";
  private static final String CONVERSATION_GROUP = "2";
  private static final String MSG_TEXT = "text";
  private static final String MSG_PICTURE = "picture";
  private static final String MSG_FILE = "file";
  private static final String MSG_AUDIO = "audio";
  private static final String MSG_VIDEO = "video";
  private static final String FIELD_IN_AT_LIST = "isInAtList";
  private static final Pattern LEADING_AT = Pattern.compile("^@\\S+\\s*");

  private final String channelName;

  public DingTalkEventNormalizer(String channelName) {
    this.channelName = channelName;
  }

  /** 归一化一条机器人消息回调 data；结构不完整返回 empty。 */
  public Optional<InboundMessage> normalize(JsonNode body) {
    if (body == null || !body.isObject()) {
      return Optional.empty();
    }
    String msgId = text(body, "msgId");
    String conversationType = text(body, "conversationType");
    String conversationId = text(body, "conversationId");
    String senderId = text(body, "senderId");
    if (senderId == null || senderId.isBlank()) {
      senderId = text(body, "senderStaffId");
    }
    if (msgId == null || conversationType == null || conversationId == null || senderId == null) {
      LOG.warn("钉钉消息缺关键字段（msgId/conversationType/conversationId/senderId），已丢弃");
      return Optional.empty();
    }
    boolean single = CONVERSATION_SINGLE.equals(conversationType);
    boolean group = CONVERSATION_GROUP.equals(conversationType);
    if (!single && !group) {
      LOG.warn("钉钉未知 conversationType={}，已丢弃", sanitize(conversationType));
      return Optional.empty();
    }
    if (group && !body.path(FIELD_IN_AT_LIST).asBoolean(false)) {
      LOG.debug("钉钉群消息未 @ 机器人，已丢弃");
      return Optional.empty();
    }
    String msgtype = text(body, "msgtype");
    boolean textual = MSG_TEXT.equals(msgtype);
    String content = "";
    List<InboundAttachment> attachments = new ArrayList<>();
    if (textual) {
      content = body.path("text").path("content").asText("");
      if (group) {
        content = LEADING_AT.matcher(content).replaceFirst("");
      }
      content = content.strip();
    } else if (MSG_PICTURE.equals(msgtype)) {
      extractPictureAttachment(body.path("content")).ifPresent(attachments::add);
    } else if (MSG_FILE.equals(msgtype)) {
      extractFileAttachment(body.path("content")).ifPresent(attachments::add);
    } else if (MSG_AUDIO.equals(msgtype)) {
      extractAudioAttachment(body.path("content")).ifPresent(attachments::add);
    } else if (MSG_VIDEO.equals(msgtype)) {
      extractVideoAttachment(body.path("content")).ifPresent(attachments::add);
    } else if (msgtype != null && !msgtype.isBlank()) {
      LOG.info("钉钉收到暂不支持的消息类型 msgtype={} msgId={}", sanitize(msgtype), sanitize(msgId));
    }
    return Optional.of(
        new InboundMessage(
            CHANNEL_TYPE,
            channelName,
            msgId,
            single ? ChatKind.P2P : ChatKind.GROUP,
            senderId,
            conversationId,
            content,
            textual,
            group,
            attachments));
  }

  private static Optional<InboundAttachment> extractPictureAttachment(JsonNode content) {
    if (content == null || content.isMissingNode()) {
      return Optional.empty();
    }
    String picUrl = content.path("picURL").asText(null);
    if (picUrl != null && !picUrl.isBlank()) {
      return Optional.of(InboundAttachment.imageUrl(picUrl));
    }
    // 官方 Stream 图片：content.downloadCode / pictureDownloadCode（无直链 URL）
    String downloadCode = content.path("downloadCode").asText(null);
    if (downloadCode != null && !downloadCode.isBlank()) {
      return Optional.of(InboundAttachment.imageReference(downloadCode));
    }
    String pictureDownloadCode = content.path("pictureDownloadCode").asText(null);
    if (pictureDownloadCode != null && !pictureDownloadCode.isBlank()) {
      return Optional.of(InboundAttachment.imageReference(pictureDownloadCode));
    }
    return Optional.empty();
  }

  private static Optional<InboundAttachment> extractFileAttachment(JsonNode content) {
    if (content == null || content.isMissingNode()) {
      return Optional.empty();
    }
    String fileName = content.path("fileName").asText(null);
    if (fileName == null || fileName.isBlank()) {
      fileName = content.path("file_name").asText(null);
    }
    String fileUrl = content.path("downloadUrl").asText(null);
    if (fileUrl == null || fileUrl.isBlank()) {
      fileUrl = content.path("fileUrl").asText(null);
    }
    if (fileUrl != null && !fileUrl.isBlank()) {
      return Optional.of(InboundAttachment.fileUrl(fileUrl, fileName));
    }
    String downloadCode = content.path("downloadCode").asText(null);
    if (downloadCode != null && !downloadCode.isBlank()) {
      return Optional.of(InboundAttachment.fileReference(downloadCode, fileName));
    }
    return Optional.empty();
  }

  private static Optional<InboundAttachment> extractAudioAttachment(JsonNode content) {
    if (content == null || content.isMissingNode()) {
      return Optional.empty();
    }
    String downloadCode = content.path("downloadCode").asText(null);
    if (downloadCode != null && !downloadCode.isBlank()) {
      return Optional.of(InboundAttachment.audioReference(downloadCode));
    }
    String fileUrl = content.path("downloadUrl").asText(null);
    if (fileUrl == null || fileUrl.isBlank()) {
      fileUrl = content.path("fileUrl").asText(null);
    }
    if (fileUrl != null && !fileUrl.isBlank()) {
      return Optional.of(InboundAttachment.audioUrl(fileUrl));
    }
    return Optional.empty();
  }

  private static Optional<InboundAttachment> extractVideoAttachment(JsonNode content) {
    if (content == null || content.isMissingNode()) {
      return Optional.empty();
    }
    String fileName = content.path("fileName").asText(null);
    String downloadCode = content.path("downloadCode").asText(null);
    if (downloadCode != null && !downloadCode.isBlank()) {
      return Optional.of(InboundAttachment.videoReference(downloadCode, fileName));
    }
    String fileUrl = content.path("downloadUrl").asText(null);
    if (fileUrl == null || fileUrl.isBlank()) {
      fileUrl = content.path("fileUrl").asText(null);
    }
    if (fileUrl != null && !fileUrl.isBlank()) {
      return Optional.of(InboundAttachment.videoUrl(fileUrl, fileName));
    }
    return Optional.empty();
  }

  private static String text(JsonNode node, String field) {
    JsonNode v = node.get(field);
    if (v == null || v.isNull()) {
      return null;
    }
    String s = v.asText();
    return s == null || s.isBlank() ? null : s;
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
