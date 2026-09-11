package io.oryxos.channel.weixinkf;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMessage;
import java.util.List;
import java.util.Optional;

/**
 * {@code kf/sync_msg} 单条 → {@link InboundMessage}。
 *
 * <p>客户来源（{@code origin=3}）：文本 + 图/文件/语音/视频（{@code media_id} → attachment reference）。
 */
final class WeixinKfEventNormalizer {

  static final String CHANNEL_TYPE = WeixinKfChannelAdapter.TYPE;
  static final int ORIGIN_CUSTOMER = 3;
  private static final String MSG_TEXT = "text";
  private static final String MSG_IMAGE = "image";
  private static final String MSG_VOICE = "voice";
  private static final String MSG_VIDEO = "video";
  private static final String MSG_FILE = "file";

  private final String channelName;

  WeixinKfEventNormalizer(String channelName) {
    this.channelName = channelName;
  }

  Optional<InboundMessage> normalize(JsonNode item) {
    if (item == null || !item.isObject()) {
      return Optional.empty();
    }
    int origin = item.path("origin").asInt(-1);
    if (origin != ORIGIN_CUSTOMER) {
      return Optional.empty();
    }
    String msgId = text(item, "msgid");
    String openKfid = text(item, "open_kfid");
    String externalUserId = text(item, "external_userid");
    if (msgId == null || openKfid == null || externalUserId == null) {
      return Optional.empty();
    }
    String chatId = WeixinKfChatTargets.chatId(openKfid, externalUserId);
    String msgType = asciiLower(text(item, "msgtype"));
    if (MSG_TEXT.equals(msgType)) {
      return normalizeText(item, msgId, externalUserId, chatId);
    }
    if (MSG_IMAGE.equals(msgType)) {
      return normalizeMedia(
          item, msgId, externalUserId, chatId, "image", InboundAttachment::imageReference);
    }
    if (MSG_VOICE.equals(msgType)) {
      return normalizeMedia(
          item, msgId, externalUserId, chatId, "voice", InboundAttachment::audioReference);
    }
    if (MSG_VIDEO.equals(msgType)) {
      return normalizeMedia(
          item,
          msgId,
          externalUserId,
          chatId,
          "video",
          mediaId -> InboundAttachment.videoReference(mediaId, null));
    }
    if (MSG_FILE.equals(msgType)) {
      String mediaId = text(item.path("file"), "media_id");
      if (mediaId == null) {
        return Optional.empty();
      }
      String fileName = text(item.path("file"), "filename");
      if (fileName == null) {
        fileName = text(item.path("file"), "file_name");
      }
      return Optional.of(
          baseMessage(
              msgId,
              externalUserId,
              chatId,
              "",
              false,
              List.of(InboundAttachment.fileReference(mediaId, fileName))));
    }
    // 其它类型（菜单/位置等）丢弃，避免刷能力说明或闲聊
    return Optional.empty();
  }

  private Optional<InboundMessage> normalizeText(
      JsonNode item, String msgId, String externalUserId, String chatId) {
    String content = text(item.path("text"), "content");
    if (content == null || content.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(
        baseMessage(msgId, externalUserId, chatId, content.strip(), true, List.of()));
  }

  private Optional<InboundMessage> normalizeMedia(
      JsonNode item,
      String msgId,
      String externalUserId,
      String chatId,
      String field,
      java.util.function.Function<String, InboundAttachment> factory) {
    String mediaId = text(item.path(field), "media_id");
    if (mediaId == null) {
      return Optional.empty();
    }
    return Optional.of(
        baseMessage(msgId, externalUserId, chatId, "", false, List.of(factory.apply(mediaId))));
  }

  private InboundMessage baseMessage(
      String msgId,
      String externalUserId,
      String chatId,
      String content,
      boolean textual,
      List<InboundAttachment> attachments) {
    return new InboundMessage(
        CHANNEL_TYPE,
        channelName,
        msgId,
        ChatKind.P2P,
        externalUserId,
        chatId,
        content,
        textual,
        false,
        attachments);
  }

  private static String text(JsonNode node, String field) {
    if (node == null || !node.isObject()) {
      return null;
    }
    JsonNode v = node.get(field);
    if (v == null || v.isNull() || !v.isTextual()) {
      return null;
    }
    String s = v.asText();
    return s == null || s.isBlank() ? null : s;
  }

  private static String asciiLower(String value) {
    if (value == null) {
      return "";
    }
    char[] chars = value.toCharArray();
    for (int i = 0; i < chars.length; i++) {
      char c = chars[i];
      if (c >= 'A' && c <= 'Z') {
        chars[i] = (char) (c + ('a' - 'A'));
      }
    }
    return new String(chars);
  }
}
