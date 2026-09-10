package io.oryxos.channel.douyin;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundMessage;
import java.util.Optional;

/**
 * 抖音私信 Webhook → {@link InboundMessage}。
 *
 * <p>MVP：私聊文本；{@code im_receive_msg} / {@code im_send_msg}，且 {@code to_user_id} 为经营者 open_id。
 */
public class DouyinEventNormalizer {

  static final String CHANNEL_TYPE = "douyin";
  static final String EVENT_RECEIVE = "im_receive_msg";
  static final String EVENT_SEND = "im_send_msg";
  static final String EVENT_VERIFY = "verify_webhook";

  private static final String FIELD_EVENT = "event";
  private static final String FIELD_FROM = "from_user_id";
  private static final String FIELD_TO = "to_user_id";
  private static final String FIELD_CONTENT = "content";
  private static final String FIELD_CONVERSATION_ID = "conversation_short_id";
  private static final String FIELD_MSG_ID = "server_message_id";
  private static final String FIELD_CONVERSATION_TYPE = "conversation_type";
  private static final String FIELD_MESSAGE_TYPE = "message_type";
  private static final String FIELD_TEXT = "text";
  private static final String MSG_TYPE_TEXT = "text";
  private static final int CONVERSATION_PRIVATE = 1;

  private final String channelName;
  private final String operatorOpenId;

  public DouyinEventNormalizer(String channelName, String operatorOpenId) {
    this.channelName = channelName;
    this.operatorOpenId = operatorOpenId == null ? "" : operatorOpenId.strip();
  }

  public Optional<InboundMessage> normalize(JsonNode root) {
    if (root == null || !root.isObject()) {
      return Optional.empty();
    }
    String event = text(root, FIELD_EVENT);
    if (!EVENT_RECEIVE.equals(event) && !EVENT_SEND.equals(event)) {
      return Optional.empty();
    }
    String from = text(root, FIELD_FROM);
    String to = text(root, FIELD_TO);
    if (from == null || to == null || operatorOpenId.isBlank()) {
      return Optional.empty();
    }
    if (!operatorOpenId.equals(to) || operatorOpenId.equals(from)) {
      return Optional.empty();
    }
    JsonNode content = root.path(FIELD_CONTENT);
    if (!content.isObject()) {
      return Optional.empty();
    }
    int conversationType = content.path(FIELD_CONVERSATION_TYPE).asInt(CONVERSATION_PRIVATE);
    if (conversationType != CONVERSATION_PRIVATE) {
      return Optional.empty();
    }
    String messageType = text(content, FIELD_MESSAGE_TYPE);
    if (messageType != null && !MSG_TYPE_TEXT.equals(asciiLower(messageType))) {
      return Optional.of(
          new InboundMessage(
              CHANNEL_TYPE,
              channelName,
              firstNonBlank(text(content, FIELD_MSG_ID), from + "-nontext"),
              ChatKind.P2P,
              from,
              DouyinChatTargets.user(from),
              "",
              false,
              false,
              java.util.List.of()));
    }
    String msgId = text(content, FIELD_MSG_ID);
    if (msgId == null) {
      return Optional.empty();
    }
    String body = extractText(content);
    if (body == null || body.isBlank()) {
      return Optional.of(
          new InboundMessage(
              CHANNEL_TYPE,
              channelName,
              msgId,
              ChatKind.P2P,
              from,
              DouyinChatTargets.user(from),
              "",
              false,
              false,
              java.util.List.of()));
    }
    return Optional.of(
        new InboundMessage(
            CHANNEL_TYPE,
            channelName,
            msgId,
            ChatKind.P2P,
            from,
            DouyinChatTargets.user(from),
            body.strip(),
            true,
            false,
            java.util.List.of()));
  }

  static String conversationId(JsonNode root) {
    if (root == null) {
      return null;
    }
    return text(root.path(FIELD_CONTENT), FIELD_CONVERSATION_ID);
  }

  static String serverMessageId(JsonNode root) {
    if (root == null) {
      return null;
    }
    return text(root.path(FIELD_CONTENT), FIELD_MSG_ID);
  }

  static String fromUserId(JsonNode root) {
    return text(root, FIELD_FROM);
  }

  private static String extractText(JsonNode content) {
    JsonNode textNode = content.get(FIELD_TEXT);
    if (textNode == null || textNode.isNull()) {
      return null;
    }
    if (textNode.isTextual()) {
      return textNode.asText();
    }
    if (textNode.isObject()) {
      return text(textNode, FIELD_TEXT);
    }
    return null;
  }

  private static String firstNonBlank(String a, String b) {
    if (a != null && !a.isBlank()) {
      return a;
    }
    return b;
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
