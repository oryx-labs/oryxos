package io.oryxos.channel.alipay;

/** chatId：{@code alipay:{appId}:user:{fromUserId}}。 */
final class AlipayChatTargets {

  private static final String PREFIX = "alipay:";
  private static final String USER_SEP = ":user:";

  private AlipayChatTargets() {}

  static String chatId(String appId, String fromUserId) {
    return PREFIX + appId + USER_SEP + fromUserId;
  }

  static Parsed parse(String chatId) {
    if (chatId == null || !chatId.startsWith(PREFIX)) {
      throw new IllegalArgumentException(
          "支付宝 chatId 须为 alipay:{appId}:user:{fromUserId}，实际: " + chatId);
    }
    int idx = chatId.indexOf(USER_SEP);
    if (idx <= PREFIX.length()) {
      throw new IllegalArgumentException("支付宝 chatId 格式错误: " + chatId);
    }
    String appId = chatId.substring(PREFIX.length(), idx).strip();
    String fromUserId = chatId.substring(idx + USER_SEP.length()).strip();
    if (appId.isEmpty() || fromUserId.isEmpty()) {
      throw new IllegalArgumentException("支付宝 chatId 缺少 appId/fromUserId: " + chatId);
    }
    return new Parsed(appId, fromUserId);
  }

  record Parsed(String appId, String fromUserId) {}
}
