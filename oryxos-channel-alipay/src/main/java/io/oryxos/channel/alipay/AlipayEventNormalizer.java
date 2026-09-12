package io.oryxos.channel.alipay;

import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundMessage;
import java.util.List;
import java.util.Optional;

/** 生活号 {@code biz_content} XML 文本消息 → {@link InboundMessage}。 */
final class AlipayEventNormalizer {

  static final String CHANNEL_TYPE = AlipayChannelAdapter.TYPE;
  private static final String MSG_TEXT = "text";
  private static final String TAG_MSG_TYPE = "MsgType";
  private static final String TAG_FROM_USER = "FromUserId";
  private static final String TAG_CONTENT = "Text";
  private static final String TAG_CONTENT_ALT = "Content";
  private static final String TAG_MSG_ID = "MsgId";
  private static final String TAG_CREATE_TIME = "CreateTime";
  private static final String TAG_APP_ID = "AppId";

  private final String channelName;
  private final String appId;

  AlipayEventNormalizer(String channelName, String appId) {
    this.channelName = channelName;
    this.appId = appId;
  }

  Optional<InboundMessage> normalize(String xml) {
    if (xml == null || xml.isBlank()) {
      return Optional.empty();
    }
    String msgType = asciiLower(AlipayCallbackXml.cdataOrText(xml, TAG_MSG_TYPE));
    if (!MSG_TEXT.equals(msgType)) {
      return Optional.empty();
    }
    String fromUser = AlipayCallbackXml.cdataOrText(xml, TAG_FROM_USER);
    String content = AlipayCallbackXml.cdataOrText(xml, TAG_CONTENT);
    if (content == null || content.isBlank()) {
      content = AlipayCallbackXml.cdataOrText(xml, TAG_CONTENT_ALT);
    }
    if (fromUser == null || fromUser.isBlank() || content == null || content.isBlank()) {
      return Optional.empty();
    }
    String msgId = AlipayCallbackXml.cdataOrText(xml, TAG_MSG_ID);
    if (msgId == null || msgId.isBlank()) {
      String createTime = AlipayCallbackXml.cdataOrText(xml, TAG_CREATE_TIME);
      msgId = fromUser + ":" + (createTime != null ? createTime : "0");
    }
    String xmlAppId = AlipayCallbackXml.cdataOrText(xml, TAG_APP_ID);
    String resolvedAppId = xmlAppId != null && !xmlAppId.isBlank() ? xmlAppId : appId;
    String chatId = AlipayChatTargets.chatId(resolvedAppId, fromUser);
    return Optional.of(
        new InboundMessage(
            CHANNEL_TYPE,
            channelName,
            msgId,
            ChatKind.P2P,
            fromUser,
            chatId,
            content.strip(),
            true,
            false,
            List.of()));
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
