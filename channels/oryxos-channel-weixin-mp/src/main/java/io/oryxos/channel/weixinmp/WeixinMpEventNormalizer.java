package io.oryxos.channel.weixinmp;

import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundMessage;
import java.util.List;
import java.util.Optional;

/** 明文 XML 文本消息 → {@link InboundMessage}。 */
final class WeixinMpEventNormalizer {

  static final String CHANNEL_TYPE = WeixinMpChannelAdapter.TYPE;
  private static final String MSG_TEXT = "text";
  private static final String TAG_MSG_TYPE = "MsgType";
  private static final String TAG_FROM_USER = "FromUserName";
  private static final String TAG_CONTENT = "Content";
  private static final String TAG_MSG_ID = "MsgId";
  private static final String TAG_CREATE_TIME = "CreateTime";

  private final String channelName;
  private final String appId;

  WeixinMpEventNormalizer(String channelName, String appId) {
    this.channelName = channelName;
    this.appId = appId;
  }

  Optional<InboundMessage> normalize(String xml) {
    if (xml == null || xml.isBlank()) {
      return Optional.empty();
    }
    String msgType = asciiLower(WeixinMpCallbackXml.cdataOrText(xml, TAG_MSG_TYPE));
    if (!MSG_TEXT.equals(msgType)) {
      return Optional.empty();
    }
    String fromUser = WeixinMpCallbackXml.cdataOrText(xml, TAG_FROM_USER);
    String content = WeixinMpCallbackXml.cdataOrText(xml, TAG_CONTENT);
    if (fromUser == null || fromUser.isBlank() || content == null || content.isBlank()) {
      return Optional.empty();
    }
    String msgId = WeixinMpCallbackXml.cdataOrText(xml, TAG_MSG_ID);
    if (msgId == null || msgId.isBlank()) {
      String createTime = WeixinMpCallbackXml.cdataOrText(xml, TAG_CREATE_TIME);
      msgId = fromUser + ":" + (createTime != null ? createTime : "0");
    }
    String chatId = WeixinMpChatTargets.chatId(appId, fromUser);
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
