package io.oryxos.channel.weixinmp;

/** chatId：{@code mp:{appId}:user:{openId}}。 */
final class WeixinMpChatTargets {

  private static final String PREFIX = "mp:";
  private static final String USER_SEP = ":user:";

  private WeixinMpChatTargets() {}

  static String chatId(String appId, String openId) {
    return PREFIX + appId + USER_SEP + openId;
  }

  static Parsed parse(String chatId) {
    if (chatId == null || !chatId.startsWith(PREFIX)) {
      throw new IllegalArgumentException("服务号 chatId 须为 mp:{appId}:user:{openId}，实际: " + chatId);
    }
    int idx = chatId.indexOf(USER_SEP);
    if (idx <= PREFIX.length()) {
      throw new IllegalArgumentException("服务号 chatId 格式错误: " + chatId);
    }
    String appId = chatId.substring(PREFIX.length(), idx).strip();
    String openId = chatId.substring(idx + USER_SEP.length()).strip();
    if (appId.isEmpty() || openId.isEmpty()) {
      throw new IllegalArgumentException("服务号 chatId 缺少 appId/openId: " + chatId);
    }
    return new Parsed(appId, openId);
  }

  record Parsed(String appId, String openId) {}
}
