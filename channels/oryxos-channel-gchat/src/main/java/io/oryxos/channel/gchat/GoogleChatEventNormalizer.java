package io.oryxos.channel.gchat;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundMessage;
import java.util.List;
import java.util.Optional;

/** Google Chat HTTP 事件 {@code MESSAGE} → {@link InboundMessage}。空间仅当带 argumentText / 注解。 */
public class GoogleChatEventNormalizer {

  static final String CHANNEL_TYPE = "gchat";
  private static final String EVENT_MESSAGE = "MESSAGE";
  private static final String FIELD_TYPE = "type";
  private static final String FIELD_MESSAGE = "message";
  private static final String FIELD_NAME = "name";
  private static final String FIELD_SENDER = "sender";
  private static final String FIELD_SPACE = "space";
  private static final String SPACE_DM = "DM";
  private static final String FIELD_ARGUMENT_TEXT = "argumentText";
  private static final String FIELD_TEXT = "text";
  private static final String FIELD_ANNOTATIONS = "annotations";

  private final String channelName;

  public GoogleChatEventNormalizer(String channelName) {
    this.channelName = channelName;
  }

  public Optional<InboundMessage> normalize(JsonNode root) {
    if (root == null || !root.isObject()) {
      return Optional.empty();
    }
    if (!EVENT_MESSAGE.equals(root.path(FIELD_TYPE).asText(""))) {
      return Optional.empty();
    }
    JsonNode message = root.path(FIELD_MESSAGE);
    String messageId = text(message, FIELD_NAME);
    String userId = text(message.path(FIELD_SENDER), FIELD_NAME);
    String chatId = text(message.path(FIELD_SPACE), FIELD_NAME);
    if (messageId == null || userId == null || chatId == null) {
      return Optional.empty();
    }
    String spaceType = message.path(FIELD_SPACE).path(FIELD_TYPE).asText("");
    boolean dm = SPACE_DM.equals(spaceType);
    String argument = message.path(FIELD_ARGUMENT_TEXT).asText("").strip();
    String text = argument.isBlank() ? message.path(FIELD_TEXT).asText("").strip() : argument;
    if (!dm) {
      boolean mentioned = !argument.isBlank() || hasAnnotations(message);
      if (!mentioned) {
        return Optional.empty();
      }
      if (text.isBlank()) {
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
              true,
              true,
              List.of()));
    }
    if (text.isBlank()) {
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
    return Optional.of(
        new InboundMessage(
            CHANNEL_TYPE,
            channelName,
            messageId,
            ChatKind.P2P,
            userId,
            chatId,
            text,
            true,
            false,
            List.of()));
  }

  private static boolean hasAnnotations(JsonNode message) {
    JsonNode annotations = message.path(FIELD_ANNOTATIONS);
    return annotations.isArray() && annotations.size() > 0;
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
