package io.oryxos.channel.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMessage;
import java.util.List;
import java.util.Optional;

/** Matrix {@code m.room.message} → {@link InboundMessage}。房间需提及 bot user id。 */
public class MatrixEventNormalizer {

  static final String CHANNEL_TYPE = "matrix";
  private static final String TYPE_ROOM_MESSAGE = "m.room.message";
  private static final String FIELD_TYPE = "type";
  private static final String FIELD_SENDER = "sender";
  private static final String FIELD_EVENT_ID = "event_id";
  private static final String FIELD_CONTENT = "content";
  private static final String FIELD_MSGTYPE = "msgtype";
  private static final String FIELD_BODY = "body";
  private static final String FIELD_URL = "url";
  private static final String MSG_IMAGE = "m.image";
  private static final String MSG_AUDIO = "m.audio";
  private static final String MSG_VIDEO = "m.video";
  private static final String MSG_FILE = "m.file";
  private static final String FIELD_MENTIONS = "m.mentions";
  private static final String FIELD_USER_IDS = "user_ids";
  private static final String DEFAULT_MXC = "mxc";

  private final String channelName;
  private final String botUserId;

  public MatrixEventNormalizer(String channelName, String botUserId) {
    this.channelName = channelName;
    this.botUserId = botUserId == null ? "" : botUserId.strip();
  }

  public Optional<InboundMessage> normalize(String roomId, JsonNode event, boolean direct) {
    if (event == null || !event.isObject()) {
      return Optional.empty();
    }
    if (!TYPE_ROOM_MESSAGE.equals(event.path(FIELD_TYPE).asText(""))) {
      return Optional.empty();
    }
    String sender = text(event, FIELD_SENDER);
    String eventId = text(event, FIELD_EVENT_ID);
    if (sender == null || eventId == null || roomId == null || roomId.isBlank()) {
      return Optional.empty();
    }
    if (!botUserId.isBlank() && botUserId.equals(sender)) {
      return Optional.empty();
    }
    JsonNode content = event.path(FIELD_CONTENT);
    String msgtype = content.path(FIELD_MSGTYPE).asText("");
    String rawBody = content.path(FIELD_BODY).asText("").strip();
    String fileName = content.path("filename").asText("");
    if (fileName.isBlank()) {
      fileName = rawBody;
    }
    List<InboundAttachment> attachments = extractAttachments(msgtype, content, fileName);
    String body = captionOrEmpty(msgtype, rawBody, fileName);
    if (!direct) {
      if (!mentionsBot(content, body)) {
        return Optional.empty();
      }
      body = stripBot(body);
      if (body.isBlank() && attachments.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(
          new InboundMessage(
              CHANNEL_TYPE,
              channelName,
              eventId,
              ChatKind.GROUP,
              sender,
              roomId,
              body,
              !body.isBlank(),
              true,
              attachments));
    }
    if (body.isBlank() && attachments.isEmpty()) {
      return Optional.of(
          new InboundMessage(
              CHANNEL_TYPE,
              channelName,
              eventId,
              ChatKind.P2P,
              sender,
              roomId,
              "",
              false,
              false,
              List.of()));
    }
    return Optional.of(
        new InboundMessage(
            CHANNEL_TYPE,
            channelName,
            eventId,
            ChatKind.P2P,
            sender,
            roomId,
            body,
            !body.isBlank(),
            false,
            attachments));
  }

  private static List<InboundAttachment> extractAttachments(
      String msgtype, JsonNode content, String fileName) {
    String mxc = content.path(FIELD_URL).asText(DEFAULT_MXC);
    if (MSG_IMAGE.equals(msgtype)) {
      return List.of(new InboundAttachment(InboundAttachment.TYPE_IMAGE, null, mxc, fileName));
    }
    if (MSG_AUDIO.equals(msgtype)) {
      return List.of(new InboundAttachment(InboundAttachment.TYPE_AUDIO, null, mxc, fileName));
    }
    if (MSG_VIDEO.equals(msgtype)) {
      return List.of(new InboundAttachment(InboundAttachment.TYPE_VIDEO, null, mxc, fileName));
    }
    if (MSG_FILE.equals(msgtype)) {
      return List.of(InboundAttachment.fileReference(mxc, fileName));
    }
    return List.of();
  }

  private static String captionOrEmpty(String msgtype, String rawBody, String fileName) {
    if (!MSG_IMAGE.equals(msgtype)
        && !MSG_AUDIO.equals(msgtype)
        && !MSG_VIDEO.equals(msgtype)
        && !MSG_FILE.equals(msgtype)) {
      return rawBody;
    }
    if (rawBody == null || rawBody.isBlank()) {
      return "";
    }
    if (rawBody.equals(fileName) || looksLikeFilename(rawBody)) {
      return "";
    }
    return rawBody;
  }

  private static boolean looksLikeFilename(String body) {
    int dot = body.lastIndexOf('.');
    return dot > 0 && dot < body.length() - 1 && !body.contains(" ");
  }

  private boolean mentionsBot(JsonNode content, String body) {
    if (botUserId.isBlank()) {
      return false;
    }
    JsonNode mentions = content.path(FIELD_MENTIONS).path(FIELD_USER_IDS);
    if (mentions.isArray()) {
      for (JsonNode id : mentions) {
        if (botUserId.equals(id.asText())) {
          return true;
        }
      }
    }
    if (body == null) {
      return false;
    }
    String lower = asciiLower(body);
    if (lower.contains(asciiLower(botUserId))) {
      return true;
    }
    String local = localMention();
    return !local.isEmpty() && lower.contains(local);
  }

  private String stripBot(String body) {
    if (body == null || botUserId.isBlank()) {
      return body == null ? "" : body;
    }
    String stripped = body.replace(botUserId, "");
    String local = localMention();
    if (!local.isEmpty()) {
      stripped = stripped.replace(local, "");
    }
    return stripped.strip();
  }

  /** Element 常显示 {@code @localpart}，不全写 MXID。 */
  private String localMention() {
    if (botUserId.length() < 2 || botUserId.charAt(0) != '@') {
      return "";
    }
    int colon = botUserId.indexOf(':');
    if (colon <= 1) {
      return "";
    }
    return asciiLower(botUserId.substring(0, colon));
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
