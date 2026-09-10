package io.oryxos.channel.qq;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundMessage;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * QQ Gateway Dispatch → {@link InboundMessage}。
 *
 * <p>MVP：{@code GROUP_AT_MESSAGE_CREATE}（事件即 @Bot）+ {@code C2C_MESSAGE_CREATE}。频道事件丢弃。
 */
public class QqEventNormalizer {

  static final String CHANNEL_TYPE = "qq";
  static final String EVENT_GROUP_AT = "GROUP_AT_MESSAGE_CREATE";
  static final String EVENT_C2C = "C2C_MESSAGE_CREATE";

  private static final Pattern MENTION = Pattern.compile("<@!?\\w+>");
  private static final String FIELD_ID = "id";
  private static final String FIELD_CONTENT = "content";
  private static final String FIELD_AUTHOR = "author";
  private static final String FIELD_GROUP_OPENID = "group_openid";
  private static final String FIELD_MEMBER_OPENID = "member_openid";
  private static final String FIELD_USER_OPENID = "user_openid";

  private final String channelName;

  public QqEventNormalizer(String channelName) {
    this.channelName = channelName;
  }

  public Optional<InboundMessage> normalize(String eventName, JsonNode data) {
    if (eventName == null || data == null || !data.isObject()) {
      return Optional.empty();
    }
    if (EVENT_GROUP_AT.equals(eventName)) {
      return normalizeGroup(data);
    }
    if (EVENT_C2C.equals(eventName)) {
      return normalizeC2c(data);
    }
    return Optional.empty();
  }

  private Optional<InboundMessage> normalizeGroup(JsonNode data) {
    String messageId = text(data, FIELD_ID);
    String groupOpenid = text(data, FIELD_GROUP_OPENID);
    JsonNode author = data.path(FIELD_AUTHOR);
    String userId = firstNonBlank(text(author, FIELD_MEMBER_OPENID), text(author, FIELD_ID));
    if (messageId == null || groupOpenid == null || userId == null) {
      return Optional.empty();
    }
    String content = stripMentions(data.path(FIELD_CONTENT).asText("")).strip();
    if (content.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(
        new InboundMessage(
            CHANNEL_TYPE,
            channelName,
            messageId,
            ChatKind.GROUP,
            userId,
            QqChatTargets.group(groupOpenid),
            content,
            true,
            true,
            List.of()));
  }

  private Optional<InboundMessage> normalizeC2c(JsonNode data) {
    String messageId = text(data, FIELD_ID);
    JsonNode author = data.path(FIELD_AUTHOR);
    String userId = firstNonBlank(text(author, FIELD_USER_OPENID), text(author, FIELD_ID));
    if (messageId == null || userId == null) {
      return Optional.empty();
    }
    String content = stripMentions(data.path(FIELD_CONTENT).asText("")).strip();
    if (content.isBlank()) {
      return Optional.of(
          new InboundMessage(
              CHANNEL_TYPE,
              channelName,
              messageId,
              ChatKind.P2P,
              userId,
              QqChatTargets.user(userId),
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
            QqChatTargets.user(userId),
            content,
            true,
            false,
            List.of()));
  }

  static String stripMentions(String content) {
    if (content == null || content.isBlank()) {
      return content == null ? "" : content;
    }
    return MENTION.matcher(content).replaceAll("").strip();
  }

  private static String firstNonBlank(String a, String b) {
    if (a != null && !a.isBlank()) {
      return a;
    }
    if (b != null && !b.isBlank()) {
      return b;
    }
    return null;
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
