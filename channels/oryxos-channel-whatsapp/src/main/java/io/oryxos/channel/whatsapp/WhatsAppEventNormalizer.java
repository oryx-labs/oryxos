package io.oryxos.channel.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** WhatsApp Cloud API webhook {@code entry[].changes[].value.messages[]} → 业务会话按 P2P 处理。 */
public class WhatsAppEventNormalizer {

  static final String CHANNEL_TYPE = "whatsapp";
  private static final String FIELD_ENTRY = "entry";
  private static final String FIELD_CHANGES = "changes";
  private static final String FIELD_VALUE = "value";
  private static final String FIELD_MESSAGES = "messages";
  private static final String FIELD_ID = "id";
  private static final String FIELD_FROM = "from";
  private static final String FIELD_TYPE = "type";
  private static final String FIELD_TEXT = "text";
  private static final String FIELD_BODY = "body";
  private static final String FIELD_IMAGE = "image";
  private static final String FIELD_AUDIO = "audio";
  private static final String FIELD_VIDEO = "video";
  private static final String FIELD_DOCUMENT = "document";
  private static final String FIELD_FILENAME = "filename";
  private static final String FIELD_TIMESTAMP = "timestamp";
  private static final String TYPE_TEXT = "text";
  private static final String TYPE_IMAGE = "image";
  private static final String TYPE_AUDIO = "audio";
  private static final String TYPE_VIDEO = "video";
  private static final String TYPE_DOCUMENT = "document";
  private static final long MILLIS_PER_SECOND = 1000L;

  private final String channelName;

  public WhatsAppEventNormalizer(String channelName) {
    this.channelName = channelName;
  }

  public List<InboundMessage> normalize(JsonNode root) {
    List<InboundMessage> out = new ArrayList<>();
    if (root == null || !root.isObject()) {
      return out;
    }
    JsonNode entries = root.path(FIELD_ENTRY);
    if (!entries.isArray()) {
      return out;
    }
    for (JsonNode entry : entries) {
      JsonNode changes = entry.path(FIELD_CHANGES);
      if (!changes.isArray()) {
        continue;
      }
      for (JsonNode change : changes) {
        JsonNode value = change.path(FIELD_VALUE);
        JsonNode messages = value.path(FIELD_MESSAGES);
        if (!messages.isArray()) {
          continue;
        }
        for (JsonNode message : messages) {
          normalizeOne(message).ifPresent(out::add);
        }
      }
    }
    return out;
  }

  private Optional<InboundMessage> normalizeOne(JsonNode message) {
    String messageId = text(message, FIELD_ID);
    String from = text(message, FIELD_FROM);
    if (messageId == null || from == null) {
      return Optional.empty();
    }
    String type = message.path(FIELD_TYPE).asText(TYPE_TEXT);
    String content = "";
    List<InboundAttachment> attachments = new ArrayList<>();
    boolean textual = false;
    if (TYPE_TEXT.equals(type)) {
      content = message.path(FIELD_TEXT).path(FIELD_BODY).asText("").strip();
      textual = !content.isBlank();
    } else if (TYPE_IMAGE.equals(type)) {
      String id = text(message.path(FIELD_IMAGE), FIELD_ID);
      if (id != null) {
        attachments.add(InboundAttachment.imageReference(id));
      }
    } else if (TYPE_AUDIO.equals(type)) {
      String id = text(message.path(FIELD_AUDIO), FIELD_ID);
      if (id != null) {
        attachments.add(InboundAttachment.audioReference(id));
      }
    } else if (TYPE_VIDEO.equals(type)) {
      String id = text(message.path(FIELD_VIDEO), FIELD_ID);
      if (id != null) {
        attachments.add(InboundAttachment.videoReference(id));
      }
    } else if (TYPE_DOCUMENT.equals(type)) {
      String id = text(message.path(FIELD_DOCUMENT), FIELD_ID);
      if (id != null) {
        attachments.add(
            InboundAttachment.fileReference(
                id, text(message.path(FIELD_DOCUMENT), FIELD_FILENAME)));
      }
    }
    if (!textual && attachments.isEmpty()) {
      return Optional.of(
          new InboundMessage(
              CHANNEL_TYPE,
              channelName,
              messageId,
              ChatKind.P2P,
              from,
              from,
              "",
              false,
              false,
              List.of()));
    }
    return Optional.of(
        new InboundMessage(
            CHANNEL_TYPE,
            channelName,
            messageId,
            ChatKind.P2P,
            from,
            from,
            content,
            textual,
            false,
            attachments));
  }

  static long timestampEpochMs(JsonNode message) {
    if (message == null) {
      return 0L;
    }
    String raw = message.path(FIELD_TIMESTAMP).asText("");
    if (raw.isBlank()) {
      return 0L;
    }
    try {
      return Long.parseLong(raw) * MILLIS_PER_SECOND;
    } catch (NumberFormatException e) {
      return 0L;
    }
  }

  static String firstFrom(JsonNode root) {
    if (root == null) {
      return null;
    }
    JsonNode entries = root.path(FIELD_ENTRY);
    if (!entries.isArray()) {
      return null;
    }
    for (JsonNode entry : entries) {
      for (JsonNode change : entry.path(FIELD_CHANGES)) {
        for (JsonNode message : change.path(FIELD_VALUE).path(FIELD_MESSAGES)) {
          String from = text(message, FIELD_FROM);
          if (from != null) {
            return from;
          }
        }
      }
    }
    return null;
  }

  static long firstTimestampMs(JsonNode root) {
    if (root == null) {
      return 0L;
    }
    JsonNode entries = root.path(FIELD_ENTRY);
    if (!entries.isArray()) {
      return 0L;
    }
    for (JsonNode entry : entries) {
      for (JsonNode change : entry.path(FIELD_CHANGES)) {
        for (JsonNode message : change.path(FIELD_VALUE).path(FIELD_MESSAGES)) {
          long ts = timestampEpochMs(message);
          if (ts > 0L) {
            return ts;
          }
        }
      }
    }
    return 0L;
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
