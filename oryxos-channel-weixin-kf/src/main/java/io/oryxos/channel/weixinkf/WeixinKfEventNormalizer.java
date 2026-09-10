package io.oryxos.channel.weixinkf;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundMessage;
import java.util.Optional;

/**
 * {@code kf/sync_msg} 单条 → {@link InboundMessage}。
 *
 * <p>MVP：仅 {@code origin=3}（微信客户）且 {@code msgtype=text}。
 */
final class WeixinKfEventNormalizer {

  static final String CHANNEL_TYPE = WeixinKfChannelAdapter.TYPE;
  static final int ORIGIN_CUSTOMER = 3;
  private static final String MSG_TEXT = "text";

  private final String channelName;

  WeixinKfEventNormalizer(String channelName) {
    this.channelName = channelName;
  }

  Optional<InboundMessage> normalize(JsonNode item) {
    if (item == null || !item.isObject()) {
      return Optional.empty();
    }
    int origin = item.path("origin").asInt(-1);
    if (origin != ORIGIN_CUSTOMER) {
      return Optional.empty();
    }
    String msgId = text(item, "msgid");
    String openKfid = text(item, "open_kfid");
    String externalUserId = text(item, "external_userid");
    if (msgId == null || openKfid == null || externalUserId == null) {
      return Optional.empty();
    }
    String chatId = WeixinKfChatTargets.chatId(openKfid, externalUserId);
    String msgType = asciiLower(text(item, "msgtype"));
    if (!MSG_TEXT.equals(msgType)) {
      return Optional.of(
          new InboundMessage(
              CHANNEL_TYPE,
              channelName,
              msgId,
              ChatKind.P2P,
              externalUserId,
              chatId,
              "",
              false,
              false,
              java.util.List.of()));
    }
    String content = text(item.path("text"), "content");
    if (content == null || content.isBlank()) {
      return Optional.of(
          new InboundMessage(
              CHANNEL_TYPE,
              channelName,
              msgId,
              ChatKind.P2P,
              externalUserId,
              chatId,
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
            externalUserId,
            chatId,
            content.strip(),
            true,
            false,
            java.util.List.of()));
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
}
