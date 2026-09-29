package io.oryxos.channel.teams;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundMessage;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Azure Bot Framework Activity → {@link InboundMessage}。频道仅当 @Bot。MVP 纯文本。 */
public class TeamsEventNormalizer {

  static final String CHANNEL_TYPE = "teams";
  private static final Pattern AT_TAG = Pattern.compile("(?i)<at>[^<]*</at>\\s*");
  private static final String TYPE_MESSAGE = "message";
  private static final String FIELD_TYPE = "type";
  private static final String FIELD_ID = "id";
  private static final String FIELD_FROM = "from";
  private static final String FIELD_CONVERSATION = "conversation";
  private static final String FIELD_CONVERSATION_TYPE = "conversationType";
  private static final String CONV_CHANNEL = "channel";
  private static final String CONV_GROUPCHAT = "groupchat";
  private static final String FIELD_TEXT = "text";
  private static final String FIELD_SERVICE_URL = "serviceUrl";
  private static final String FIELD_ENTITIES = "entities";
  private static final String ENTITY_MENTION = "mention";
  private static final String FIELD_MENTIONED = "mentioned";
  private static final String AT_MARKER = "<at>";

  private final String channelName;
  private final String appId;

  public TeamsEventNormalizer(String channelName, String appId) {
    this.channelName = channelName;
    this.appId = appId == null ? "" : appId.strip();
  }

  public Optional<InboundMessage> normalize(JsonNode activity) {
    if (activity == null || !activity.isObject()) {
      return Optional.empty();
    }
    if (!TYPE_MESSAGE.equals(activity.path(FIELD_TYPE).asText(""))) {
      return Optional.empty();
    }
    String messageId = text(activity, FIELD_ID);
    String userId = text(activity.path(FIELD_FROM), FIELD_ID);
    String chatId = text(activity.path(FIELD_CONVERSATION), FIELD_ID);
    if (messageId == null || userId == null || chatId == null) {
      return Optional.empty();
    }
    String convType =
        asciiLower(activity.path(FIELD_CONVERSATION).path(FIELD_CONVERSATION_TYPE).asText(""));
    boolean group = CONV_CHANNEL.equals(convType) || CONV_GROUPCHAT.equals(convType);
    String text = activity.path(FIELD_TEXT).asText("").strip();
    if (group) {
      if (!mentionsBot(activity, text)) {
        return Optional.empty();
      }
      text = AT_TAG.matcher(text).replaceAll("").strip();
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

  static String serviceUrl(JsonNode activity) {
    return activity == null ? null : text(activity, FIELD_SERVICE_URL);
  }

  private boolean mentionsBot(JsonNode activity, String text) {
    JsonNode entities = activity.path(FIELD_ENTITIES);
    if (entities.isArray()) {
      for (JsonNode entity : entities) {
        if (!ENTITY_MENTION.equals(entity.path(FIELD_TYPE).asText(""))) {
          continue;
        }
        if (mentionsAppId(text(entity.path(FIELD_MENTIONED), FIELD_ID))) {
          return true;
        }
      }
    }
    return text != null && asciiLower(text).contains(AT_MARKER);
  }

  private boolean mentionsAppId(String mentioned) {
    if (mentioned == null) {
      return false;
    }
    return mentioned.contains(appId) || mentioned.equals(appId);
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
