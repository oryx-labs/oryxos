package io.oryxos.channel.weixin;

/** chatId：私聊 {@code user:{from_user_id}}。 */
final class WeixinChatTargets {

  private static final String PREFIX_USER = "user:";

  private WeixinChatTargets() {}

  static String user(String userId) {
    return PREFIX_USER + userId;
  }

  static String parseUserId(String chatId) {
    if (chatId == null || !chatId.startsWith(PREFIX_USER)) {
      throw new IllegalArgumentException("微信 chatId 须为 user:{id}，实际: " + chatId);
    }
    String id = chatId.substring(PREFIX_USER.length()).strip();
    if (id.isEmpty()) {
      throw new IllegalArgumentException("微信 chatId 缺少 user id");
    }
    return id;
  }
}
