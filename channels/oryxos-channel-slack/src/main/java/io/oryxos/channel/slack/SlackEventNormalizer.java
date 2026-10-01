package io.oryxos.channel.slack;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Slack Events API 事件 → 归一化 {@link InboundMessage}。
 *
 * <p>私聊 {@code message}（含 {@code file_share}）与群 {@code app_mention}；忽略 bot 自身与编辑/删除等 subtype。
 */
public class SlackEventNormalizer {

  private static final Logger LOG = LoggerFactory.getLogger(SlackEventNormalizer.class);

  static final String CHANNEL_TYPE = "slack";
  private static final String EVENT_MESSAGE = "message";
  private static final String EVENT_APP_MENTION = "app_mention";
  private static final String CHANNEL_TYPE_IM = "im";
  private static final String CHANNEL_TYPE_MPIM = "mpim";
  private static final String FIELD_BOT_ID = "bot_id";
  private static final String FIELD_FILES = "files";
  private static final String SUBTYPE_FILE_SHARE = "file_share";
  private static final String MIME_IMAGE_PREFIX = "image/";
  private static final Set<String> ALLOWED_SUBTYPES = Set.of(SUBTYPE_FILE_SHARE);
  private static final Pattern MENTION = Pattern.compile("<@[A-Z0-9]+>\\s*");

  private final String channelName;

  public SlackEventNormalizer(String channelName) {
    this.channelName = channelName;
  }

  /** 归一化一条 Events API {@code event} 对象；不支持或结构不完整返回 empty。 */
  public Optional<InboundMessage> normalize(JsonNode event) {
    if (event == null || !event.isObject()) {
      return Optional.empty();
    }
    String type = text(event, "type");
    if (type == null) {
      return Optional.empty();
    }
    if (EVENT_APP_MENTION.equals(type)) {
      return normalizeAppMention(event);
    }
    if (EVENT_MESSAGE.equals(type)) {
      return normalizeMessage(event);
    }
    LOG.info("Slack 收到暂不支持的事件类型 type={}", sanitize(type));
    return Optional.empty();
  }

  private Optional<InboundMessage> normalizeAppMention(JsonNode event) {
    if (shouldDrop(event)) {
      return Optional.empty();
    }
    String userId = text(event, "user");
    String chatId = text(event, "channel");
    String messageId = text(event, "ts");
    if (userId == null || chatId == null || messageId == null) {
      LOG.warn("Slack app_mention 缺关键字段（user/channel/ts），已丢弃");
      return Optional.empty();
    }
    String content = stripMentions(event.path("text").asText("")).strip();
    List<InboundAttachment> attachments = extractFiles(event.path(FIELD_FILES));
    if (content.isBlank() && attachments.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new InboundMessage(
            CHANNEL_TYPE,
            channelName,
            messageId,
            ChatKind.GROUP,
            userId,
            chatId,
            content,
            !content.isBlank(),
            true,
            attachments));
  }

  private Optional<InboundMessage> normalizeMessage(JsonNode event) {
    if (shouldDrop(event)) {
      return Optional.empty();
    }
    String channelType = text(event, "channel_type");
    boolean dm = CHANNEL_TYPE_IM.equals(channelType) || CHANNEL_TYPE_MPIM.equals(channelType);
    if (!dm) {
      return Optional.empty();
    }
    String userId = text(event, "user");
    String chatId = text(event, "channel");
    String messageId = text(event, "ts");
    if (userId == null || chatId == null || messageId == null) {
      LOG.warn("Slack 私聊消息缺关键字段（user/channel/ts），已丢弃");
      return Optional.empty();
    }
    String content = event.path("text").asText("").strip();
    List<InboundAttachment> attachments = extractFiles(event.path(FIELD_FILES));
    if (content.isBlank() && attachments.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new InboundMessage(
            CHANNEL_TYPE,
            channelName,
            messageId,
            ChatKind.P2P,
            userId,
            chatId,
            content,
            !content.isBlank(),
            false,
            attachments));
  }

  /** 丢弃 bot 消息与不可处理的 subtype；保留空 subtype 与 {@code file_share}（图片/文件入站）。 */
  static boolean shouldDrop(JsonNode event) {
    if (event.hasNonNull(FIELD_BOT_ID)) {
      return true;
    }
    String subtype = text(event, "subtype");
    if (subtype == null || subtype.isBlank()) {
      return false;
    }
    return !ALLOWED_SUBTYPES.contains(subtype);
  }

  static List<InboundAttachment> extractFiles(JsonNode files) {
    List<InboundAttachment> out = new ArrayList<>();
    if (files == null || !files.isArray()) {
      return out;
    }
    for (JsonNode file : files) {
      if (file == null || !file.isObject()) {
        continue;
      }
      String url = text(file, "url_private_download");
      if (url == null) {
        url = text(file, "url_private");
      }
      if (url == null) {
        continue;
      }
      String fileName = text(file, "name");
      String mime = text(file, "mimetype");
      if (mime != null && mime.toLowerCase(Locale.ROOT).startsWith(MIME_IMAGE_PREFIX)) {
        out.add(new InboundAttachment(InboundAttachment.TYPE_IMAGE, url, null, fileName));
      } else {
        out.add(InboundAttachment.fileUrl(url, fileName));
      }
    }
    return out;
  }

  static String stripMentions(String text) {
    if (text == null || text.isBlank()) {
      return "";
    }
    return MENTION.matcher(text).replaceAll("").strip();
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
