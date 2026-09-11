package io.oryxos.channel.weixinkf;

/** chatId：{@code kf:{open_kfid}:user:{external_userid}}。 */
final class WeixinKfChatTargets {

  private static final String PREFIX = "kf:";
  private static final String USER_SEP = ":user:";

  private WeixinKfChatTargets() {}

  static String chatId(String openKfid, String externalUserId) {
    return PREFIX + openKfid + USER_SEP + externalUserId;
  }

  static Parsed parse(String chatId) {
    if (chatId == null || !chatId.startsWith(PREFIX)) {
      throw new IllegalArgumentException("微信客服 chatId 须为 kf:{open_kfid}:user:{id}，实际: " + chatId);
    }
    int idx = chatId.indexOf(USER_SEP);
    if (idx <= PREFIX.length()) {
      throw new IllegalArgumentException("微信客服 chatId 格式错误: " + chatId);
    }
    String openKfid = chatId.substring(PREFIX.length(), idx).strip();
    String userId = chatId.substring(idx + USER_SEP.length()).strip();
    if (openKfid.isEmpty() || userId.isEmpty()) {
      throw new IllegalArgumentException("微信客服 chatId 缺少 open_kfid/user: " + chatId);
    }
    return new Parsed(openKfid, userId);
  }

  record Parsed(String openKfid, String externalUserId) {}
}
