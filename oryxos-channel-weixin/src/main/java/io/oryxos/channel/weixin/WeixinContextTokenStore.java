package io.oryxos.channel.weixin;

import java.util.concurrent.ConcurrentHashMap;

/** 入站缓存的 context_token；回复必须带回。 */
final class WeixinContextTokenStore {

  private final ConcurrentHashMap<String, String> byUserId = new ConcurrentHashMap<>();

  void remember(String userId, String contextToken) {
    if (userId == null || userId.isBlank() || contextToken == null || contextToken.isBlank()) {
      return;
    }
    byUserId.put(userId, contextToken);
  }

  String require(String userId) {
    String token = byUserId.get(userId);
    if (token == null || token.isBlank()) {
      throw new IllegalStateException("微信 iLink 缺少 context_token，需用户先发一条私信（user=" + userId + "）");
    }
    return token;
  }
}
