package io.oryxos.channel.weixinmini;

/** chatId：{@code mini:{appId}:user:{openId}}。 */
final class WeixinMiniChatTargets {

  private static final String PREFIX = "mini:";
  private static final String USER_SEP = ":user:";

  private WeixinMiniChatTargets() {}

  static String chatId(String appId, String openId) {
    return PREFIX + appId + USER_SEP + openId;
  }

  static Parsed parse(String chatId) {
    if (chatId == null || !chatId.startsWith(PREFIX)) {
      throw new IllegalArgumentException("小程序 chatId 须为 mini:{appId}:user:{openId}，实际: " + chatId);
    }
    int idx = chatId.indexOf(USER_SEP);
    if (idx <= PREFIX.length()) {
      throw new IllegalArgumentException("小程序 chatId 格式错误: " + chatId);
    }
    String appId = chatId.substring(PREFIX.length(), idx).strip();
    String openId = chatId.substring(idx + USER_SEP.length()).strip();
    if (appId.isEmpty() || openId.isEmpty()) {
      throw new IllegalArgumentException("小程序 chatId 缺少 appId/openId: " + chatId);
    }
    return new Parsed(appId, openId);
  }

  record Parsed(String appId, String openId) {}
}
