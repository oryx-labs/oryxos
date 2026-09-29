package io.oryxos.channel.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Telegram {@code Update.message} → {@link InboundMessage}。群聊仅当 {@code @} bot 用户名时接受。 */
public class TelegramEventNormalizer {

  static final String CHANNEL_TYPE = "telegram";
  private static final Pattern MENTION = Pattern.compile("@[A-Za-z0-9_]+\\s*");
  private static final String FIELD_MESSAGE = "message";
  private static final String FIELD_EDITED_MESSAGE = "edited_message";
  private static final String FIELD_FROM = "from";
  private static final String FIELD_IS_BOT = "is_bot";
  private static final String FIELD_ID = "id";
  private static final String FIELD_CHAT = "chat";
  private static final String FIELD_MESSAGE_ID = "message_id";
  private static final String FIELD_TYPE = "type";
  private static final String CHAT_GROUP = "group";
  private static final String CHAT_SUPERGROUP = "supergroup";
  private static final String FIELD_TEXT = "text";
  private static final String FIELD_CAPTION = "caption";
  private static final String FIELD_STICKER = "sticker";
  private static final String FIELD_LOCATION = "location";
  private static final String FIELD_ENTITIES = "entities";
  private static final String FIELD_CAPTION_ENTITIES = "caption_entities";
  private static final String ENTITY_MENTION = "mention";
  private static final String ENTITY_TEXT_MENTION = "text_mention";
  private static final String FIELD_USERNAME = "username";
  private static final String FIELD_USER = "user";
  private static final String FIELD_OFFSET = "offset";
  private static final String FIELD_LENGTH = "length";
  private static final String FIELD_PHOTO = "photo";
  private static final String FIELD_FILE_ID = "file_id";
  private static final String FIELD_VOICE = "voice";
  private static final String FIELD_AUDIO = "audio";
  private static final String FIELD_DOCUMENT = "document";
  private static final String FIELD_FILE_NAME = "file_name";
  private static final String FIELD_MIME_TYPE = "mime_type";
  private static final String FIELD_VIDEO = "video";
  private static final String MIME_IMAGE_PREFIX = "image/";
  private static final String MIME_AUDIO_PREFIX = "audio/";
  private static final String MIME_VIDEO_PREFIX = "video/";
  private static final String AT_PREFIX = "@";

  private final String channelName;
  private final String botUsername;

  public TelegramEventNormalizer(String channelName, String botUsername) {
    this.channelName = channelName;
    this.botUsername = botUsername == null ? "" : stripAt(botUsername);
  }

  public Optional<InboundMessage> normalize(JsonNode update) {
    if (update == null || !update.isObject()) {
      return Optional.empty();
    }
    JsonNode message = update.path(FIELD_MESSAGE);
    if (message.isMissingNode() || !message.isObject()) {
      message = update.path(FIELD_EDITED_MESSAGE);
    }
    if (!message.isObject()) {
      return Optional.empty();
    }
    JsonNode from = message.path(FIELD_FROM);
    if (from.path(FIELD_IS_BOT).asBoolean(false)) {
      return Optional.empty();
    }
    String userId = text(from, FIELD_ID);
    JsonNode chat = message.path(FIELD_CHAT);
    String chatId = text(chat, FIELD_ID);
    String messageId = text(message, FIELD_MESSAGE_ID);
    if (userId == null || chatId == null || messageId == null) {
      return Optional.empty();
    }
    String chatType = chat.path(FIELD_TYPE).asText("");
    boolean group = CHAT_GROUP.equals(chatType) || CHAT_SUPERGROUP.equals(chatType);
    String text = message.path(FIELD_TEXT).asText("");
    if (text.isBlank()) {
      text = message.path(FIELD_CAPTION).asText("");
    }
    List<InboundAttachment> attachments = extractAttachments(message);
    if (group) {
      if (!mentionsBot(message, text)) {
        return Optional.empty();
      }
      text = stripBotMention(text).strip();
      if (text.isBlank() && attachments.isEmpty()) {
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
              text,
              !text.isBlank(),
              true,
              attachments));
    }
    if (text.isBlank() && attachments.isEmpty()) {
      if (message.has(FIELD_STICKER) || message.has(FIELD_LOCATION)) {
        return Optional.of(
            new InboundMessage(
                CHANNEL_TYPE,
                channelName,
                messageId,
                ChatKind.P2P,
                userId,
                chatId,
                "",
                false,
                false,
                List.of()));
      }
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
            text,
            !text.isBlank(),
            false,
            attachments));
  }

  private boolean mentionsBot(JsonNode message, String text) {
    if (botUsername.isBlank()) {
      return false;
    }
    String needle = AT_PREFIX + asciiLower(botUsername);
    if (text != null && asciiLower(text).contains(needle)) {
      return true;
    }
    JsonNode entities = message.path(FIELD_ENTITIES);
    if (!entities.isArray()) {
      entities = message.path(FIELD_CAPTION_ENTITIES);
    }
    if (entities.isArray()) {
      for (JsonNode entity : entities) {
        String type = entity.path(FIELD_TYPE).asText("");
        if (ENTITY_MENTION.equals(type) && text != null) {
          int offset = entity.path(FIELD_OFFSET).asInt(0);
          int length = entity.path(FIELD_LENGTH).asInt(0);
          if (offset >= 0 && offset + length <= text.length()) {
            String frag = asciiLower(text.substring(offset, offset + length));
            if (frag.equals(needle)) {
              return true;
            }
          }
        }
        if (ENTITY_TEXT_MENTION.equals(type)
            && asciiLower(botUsername)
                .equals(asciiLower(entity.path(FIELD_USER).path(FIELD_USERNAME).asText("")))) {
          return true;
        }
      }
    }
    return false;
  }

  private String stripBotMention(String text) {
    if (text == null || text.isBlank() || botUsername.isBlank()) {
      return text == null ? "" : text;
    }
    return MENTION.matcher(text).replaceAll("").strip();
  }

  static List<InboundAttachment> extractAttachments(JsonNode message) {
    List<InboundAttachment> out = new ArrayList<>();
    JsonNode photos = message.path(FIELD_PHOTO);
    if (photos.isArray() && photos.size() > 0) {
      JsonNode best = photos.get(photos.size() - 1);
      String fileId = text(best, FIELD_FILE_ID);
      if (fileId != null) {
        out.add(InboundAttachment.imageReference(fileId));
      }
    }
    JsonNode voice = message.path(FIELD_VOICE);
    if (voice.isObject()) {
      String fileId = text(voice, FIELD_FILE_ID);
      if (fileId != null) {
        out.add(InboundAttachment.audioReference(fileId));
      }
    }
    JsonNode audio = message.path(FIELD_AUDIO);
    if (audio.isObject()) {
      String fileId = text(audio, FIELD_FILE_ID);
      if (fileId != null) {
        out.add(
            new InboundAttachment(
                InboundAttachment.TYPE_AUDIO, null, fileId, text(audio, FIELD_FILE_NAME)));
      }
    }
    JsonNode document = message.path(FIELD_DOCUMENT);
    if (document.isObject()) {
      String fileId = text(document, FIELD_FILE_ID);
      if (fileId != null) {
        String mime = document.path(FIELD_MIME_TYPE).asText("");
        String name = text(document, FIELD_FILE_NAME);
        if (mime.startsWith(MIME_IMAGE_PREFIX)) {
          out.add(new InboundAttachment(InboundAttachment.TYPE_IMAGE, null, fileId, name));
        } else if (mime.startsWith(MIME_AUDIO_PREFIX)) {
          out.add(new InboundAttachment(InboundAttachment.TYPE_AUDIO, null, fileId, name));
        } else if (mime.startsWith(MIME_VIDEO_PREFIX)) {
          out.add(new InboundAttachment(InboundAttachment.TYPE_VIDEO, null, fileId, name));
        } else {
          out.add(InboundAttachment.fileReference(fileId, name));
        }
      }
    }
    JsonNode video = message.path(FIELD_VIDEO);
    if (video.isObject()) {
      String fileId = text(video, FIELD_FILE_ID);
      if (fileId != null) {
        out.add(InboundAttachment.videoReference(fileId, text(video, FIELD_FILE_NAME)));
      }
    }
    return out;
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

  private static String stripAt(String username) {
    String s = username.strip();
    return s.startsWith(AT_PREFIX) ? s.substring(1) : s;
  }

  private static String text(JsonNode node, String field) {
    if (node == null || !node.isObject()) {
      return null;
    }
    JsonNode v = node.get(field);
    if (v == null || v.isNull()) {
      return null;
    }
    String s = v.asText();
    return s == null || s.isBlank() ? null : s;
  }
}
