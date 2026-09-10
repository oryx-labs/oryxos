package io.oryxos.channel.weixin;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMessage;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * iLink {@code msgs[]} → {@link InboundMessage}。
 *
 * <p>私聊文本 + 图/语音/文件/视频（CDN 引用；落盘由 {@link WeixinInboundMediaResolver}）。
 */
public class WeixinEventNormalizer {

  static final String CHANNEL_TYPE = "weixin";
  static final String DEFAULT_CDN_BASE = "https://novac2c.cdn.weixin.qq.com/c2c";

  private static final int ITEM_TEXT = 1;
  private static final int ITEM_IMAGE = 2;
  private static final int ITEM_VOICE = 3;
  private static final int ITEM_FILE = 4;
  private static final int ITEM_VIDEO = 5;

  private static final String FIELD_FROM = "from_user_id";
  private static final String FIELD_MSG_ID = "message_id";
  private static final String FIELD_CONTEXT = "context_token";
  private static final String FIELD_ROOM = "room_id";
  private static final String FIELD_CHAT_ROOM = "chat_room_id";
  private static final String FIELD_ITEM_LIST = "item_list";
  private static final String FIELD_TYPE = "type";
  private static final String FIELD_TEXT_ITEM = "text_item";
  private static final String FIELD_TEXT = "text";
  private static final String FIELD_IMAGE_ITEM = "image_item";
  private static final String FIELD_VOICE_ITEM = "voice_item";
  private static final String FIELD_FILE_ITEM = "file_item";
  private static final String FIELD_VIDEO_ITEM = "video_item";
  private static final String FIELD_MEDIA = "media";
  private static final String FIELD_ENCRYPT = "encrypt_query_param";
  private static final String FIELD_AES_KEY = "aes_key";
  private static final String FIELD_AESKEY = "aeskey";
  private static final String FIELD_FULL_URL = "full_url";
  private static final String FIELD_FILE_NAME = "file_name";

  private final String channelName;
  private final String accountId;
  private final String cdnBaseUrl;

  public WeixinEventNormalizer(String channelName, String accountId) {
    this(channelName, accountId, DEFAULT_CDN_BASE);
  }

  public WeixinEventNormalizer(String channelName, String accountId, String cdnBaseUrl) {
    this.channelName = channelName;
    this.accountId = accountId == null ? "" : accountId.strip();
    this.cdnBaseUrl =
        cdnBaseUrl == null || cdnBaseUrl.isBlank() ? DEFAULT_CDN_BASE : trimSlash(cdnBaseUrl);
  }

  public Optional<InboundMessage> normalize(JsonNode message) {
    if (message == null || !message.isObject()) {
      return Optional.empty();
    }
    if (isGroup(message)) {
      return Optional.empty();
    }
    String from = text(message, FIELD_FROM);
    if (from == null || from.equals(accountId)) {
      return Optional.empty();
    }
    String msgId = text(message, FIELD_MSG_ID);
    if (msgId == null) {
      msgId = from + "-" + System.currentTimeMillis();
    }
    Extracted extracted = extractItems(message.path(FIELD_ITEM_LIST));
    if ((extracted.text == null || extracted.text.isBlank()) && extracted.attachments.isEmpty()) {
      return Optional.empty();
    }
    String body = extracted.text == null ? "" : extracted.text.strip();
    boolean textual = !body.isBlank();
    return Optional.of(
        new InboundMessage(
            CHANNEL_TYPE,
            channelName,
            msgId,
            ChatKind.P2P,
            from,
            WeixinChatTargets.user(from),
            body,
            textual,
            false,
            List.copyOf(extracted.attachments)));
  }

  static String contextToken(JsonNode message) {
    return text(message, FIELD_CONTEXT);
  }

  static String fromUserId(JsonNode message) {
    return text(message, FIELD_FROM);
  }

  private static boolean isGroup(JsonNode message) {
    return text(message, FIELD_ROOM) != null || text(message, FIELD_CHAT_ROOM) != null;
  }

  private Extracted extractItems(JsonNode itemList) {
    StringBuilder text = new StringBuilder();
    List<InboundAttachment> attachments = new ArrayList<>();
    if (itemList == null || !itemList.isArray()) {
      return new Extracted(null, attachments);
    }
    for (JsonNode item : itemList) {
      int type = item.path(FIELD_TYPE).asInt(-1);
      if (type == ITEM_TEXT) {
        appendText(text, text(item.path(FIELD_TEXT_ITEM), FIELD_TEXT));
        continue;
      }
      if (type == ITEM_VOICE) {
        appendText(text, text(item.path(FIELD_VOICE_ITEM), FIELD_TEXT));
      }
      InboundAttachment attachment = attachmentOf(item, type);
      if (attachment != null) {
        attachments.add(attachment);
      }
    }
    return new Extracted(text.length() == 0 ? null : text.toString(), attachments);
  }

  private InboundAttachment attachmentOf(JsonNode item, int type) {
    return switch (type) {
      case ITEM_IMAGE -> imageAttachment(item);
      case ITEM_VOICE ->
          mediaAttachment(item.path(FIELD_VOICE_ITEM), InboundAttachment.TYPE_AUDIO, "voice.silk");
      case ITEM_FILE -> fileAttachment(item.path(FIELD_FILE_ITEM));
      case ITEM_VIDEO ->
          mediaAttachment(item.path(FIELD_VIDEO_ITEM), InboundAttachment.TYPE_VIDEO, "video.mp4");
      default -> null;
    };
  }

  private InboundAttachment imageAttachment(JsonNode item) {
    JsonNode imageItem = item.path(FIELD_IMAGE_ITEM);
    JsonNode media = imageItem.path(FIELD_MEDIA);
    String url = downloadUrl(media);
    if (url == null) {
      return null;
    }
    String aes = imageAesKey(imageItem, media);
    return new InboundAttachment(InboundAttachment.TYPE_IMAGE, url, aes, "image.jpg");
  }

  private InboundAttachment fileAttachment(JsonNode fileItem) {
    if (fileItem == null || !fileItem.isObject()) {
      return null;
    }
    JsonNode media = fileItem.path(FIELD_MEDIA);
    String url = downloadUrl(media);
    if (url == null) {
      return null;
    }
    String name = text(fileItem, FIELD_FILE_NAME);
    if (name == null) {
      name = "document.bin";
    }
    return new InboundAttachment(
        InboundAttachment.TYPE_FILE, url, text(media, FIELD_AES_KEY), name);
  }

  private InboundAttachment mediaAttachment(JsonNode typedItem, String type, String defaultName) {
    if (typedItem == null || !typedItem.isObject()) {
      return null;
    }
    JsonNode media = typedItem.path(FIELD_MEDIA);
    String url = downloadUrl(media);
    if (url == null) {
      return null;
    }
    String name = text(typedItem, FIELD_FILE_NAME);
    if (name == null) {
      name = defaultName;
    }
    return new InboundAttachment(type, url, text(media, FIELD_AES_KEY), name);
  }

  private String downloadUrl(JsonNode media) {
    if (media == null || !media.isObject()) {
      return null;
    }
    String enc = text(media, FIELD_ENCRYPT);
    if (enc != null) {
      return cdnBaseUrl
          + "/download?encrypted_query_param="
          + URLEncoder.encode(enc, StandardCharsets.UTF_8);
    }
    return text(media, FIELD_FULL_URL);
  }

  /** 对齐 Hermes：优先 image_item.aeskey（hex）→ base64(hex 字符串)；否则 media.aes_key。 */
  private static String imageAesKey(JsonNode imageItem, JsonNode media) {
    String hex = text(imageItem, FIELD_AESKEY);
    if (hex != null && hex.matches("(?i)[0-9a-f]{32}")) {
      return Base64.getEncoder().encodeToString(hex.getBytes(StandardCharsets.US_ASCII));
    }
    return text(media, FIELD_AES_KEY);
  }

  private static void appendText(StringBuilder sb, String value) {
    if (value == null || value.isBlank()) {
      return;
    }
    if (sb.length() > 0) {
      sb.append('\n');
    }
    sb.append(value);
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

  private static String trimSlash(String base) {
    String s = base.strip();
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }

  private record Extracted(String text, List<InboundAttachment> attachments) {}
}
