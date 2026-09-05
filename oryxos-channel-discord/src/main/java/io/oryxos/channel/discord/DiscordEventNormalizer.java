package io.oryxos.channel.discord;

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
 * Discord Gateway {@code MESSAGE_CREATE} → 归一化 {@link InboundMessage}。
 *
 * <p>私聊（无 {@code guild_id}）收文本/附件；公会频道仅当提及本 Bot（Application ID）时接受。
 */
public class DiscordEventNormalizer {

  private static final Logger LOG = LoggerFactory.getLogger(DiscordEventNormalizer.class);

  static final String CHANNEL_TYPE = "discord";
  private static final String EVENT_MESSAGE_CREATE = "MESSAGE_CREATE";
  private static final String FIELD_AUTHOR = "author";
  private static final String FIELD_BOT = "bot";
  private static final String FIELD_WEBHOOK_ID = "webhook_id";
  private static final String FIELD_ATTACHMENTS = "attachments";
  private static final String MIME_IMAGE_PREFIX = "image/";
  private static final Pattern MENTION = Pattern.compile("<@!?([0-9]+)>\\s*");

  private final String channelName;
  private final String applicationId;

  public DiscordEventNormalizer(String channelName, String applicationId) {
    this.channelName = channelName;
    this.applicationId = applicationId == null ? "" : applicationId.strip();
  }

  /** 归一化一条 Gateway Dispatch 的 {@code d} 载荷；{@code t} 非 MESSAGE_CREATE 时返回 empty。 */
  public Optional<InboundMessage> normalize(String eventName, JsonNode data) {
    if (eventName == null || data == null || !data.isObject()) {
      return Optional.empty();
    }
    if (!EVENT_MESSAGE_CREATE.equals(eventName)) {
      return Optional.empty();
    }
    JsonNode author = data.path(FIELD_AUTHOR);
    if (author.path(FIELD_BOT).asBoolean(false)) {
      return Optional.empty();
    }
    if (data.hasNonNull(FIELD_WEBHOOK_ID)) {
      return Optional.empty();
    }
    String userId = text(author, "id");
    String chatId = text(data, "channel_id");
    String messageId = text(data, "id");
    if (userId == null || chatId == null || messageId == null) {
      LOG.warn("Discord MESSAGE_CREATE 缺关键字段（author.id/channel_id/id），已丢弃");
      return Optional.empty();
    }
    String content = data.path("content").asText("").strip();
    List<InboundAttachment> attachments = extractAttachments(data.path(FIELD_ATTACHMENTS));
    boolean inGuild = data.hasNonNull("guild_id") && !data.path("guild_id").asText("").isBlank();
    if (inGuild) {
      if (!mentionsBot(data, content)) {
        return Optional.empty();
      }
      content = stripMentions(content).strip();
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

  static List<InboundAttachment> extractAttachments(JsonNode attachments) {
    List<InboundAttachment> out = new ArrayList<>();
    if (attachments == null || !attachments.isArray()) {
      return out;
    }
    for (JsonNode file : attachments) {
      if (file == null || !file.isObject()) {
        continue;
      }
      String url = text(file, "url");
      if (url == null) {
        url = text(file, "proxy_url");
      }
      if (url == null) {
        continue;
      }
      String fileName = text(file, "filename");
      String contentType = text(file, "content_type");
      if (contentType != null && asciiLower(contentType).startsWith(MIME_IMAGE_PREFIX)) {
        out.add(new InboundAttachment(InboundAttachment.TYPE_IMAGE, url, null, fileName));
      } else {
        out.add(InboundAttachment.fileUrl(url, fileName));
      }
    }
    return out;
  }

  private static String asciiLower(String value) {
    char[] chars = value.toCharArray();
    for (int i = 0; i < chars.length; i++) {
      char c = chars[i];
      if (c >= 'A' && c <= 'Z') {
        chars[i] = (char) (c + ('a' - 'A'));
      }
    }
    return new String(chars);
  }

  private boolean mentionsBot(JsonNode data, String content) {
    if (applicationId.isBlank()) {
      return false;
    }
    JsonNode mentions = data.path("mentions");
    if (mentions.isArray()) {
      for (JsonNode m : mentions) {
        if (applicationId.equals(text(m, "id"))) {
          return true;
        }
      }
    }
    if (content == null) {
      return false;
    }
    return content.contains("<@" + applicationId + ">")
        || content.contains("<@!" + applicationId + ">");
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
}
