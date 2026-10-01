package io.oryxos.channel.douyin;

/** chatId 编码：仅私聊 {@code user:{open_id}}。 */
final class DouyinChatTargets {

  private static final String PREFIX_USER = "user:";

  private DouyinChatTargets() {}

  static String user(String openId) {
    return PREFIX_USER + openId;
  }

  static String parseUserOpenId(String chatId) {
    if (chatId == null || !chatId.startsWith(PREFIX_USER)) {
      throw new IllegalArgumentException("抖音 chatId 须为 user:{open_id}，实际: " + chatId);
    }
    String id = chatId.substring(PREFIX_USER.length()).strip();
    if (id.isEmpty()) {
      throw new IllegalArgumentException("抖音 chatId 缺少 open_id");
    }
    return id;
  }
}
