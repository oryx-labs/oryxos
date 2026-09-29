package io.oryxos.channel.qq;

/** chatId 编码：群 {@code group:{openid}}，单聊 {@code user:{openid}}。 */
final class QqChatTargets {

  static final String PREFIX_GROUP = "group:";
  static final String PREFIX_USER = "user:";

  private QqChatTargets() {}

  static String group(String groupOpenid) {
    return PREFIX_GROUP + groupOpenid;
  }

  static String user(String userOpenid) {
    return PREFIX_USER + userOpenid;
  }

  static boolean isGroup(String chatId) {
    return chatId != null && chatId.startsWith(PREFIX_GROUP);
  }

  static boolean isUser(String chatId) {
    return chatId != null && chatId.startsWith(PREFIX_USER);
  }

  static String openid(String chatId) {
    if (isGroup(chatId)) {
      return chatId.substring(PREFIX_GROUP.length());
    }
    if (isUser(chatId)) {
      return chatId.substring(PREFIX_USER.length());
    }
    throw new IllegalArgumentException("非法 QQ chatId（需 group: 或 user: 前缀）: " + chatId);
  }
}
