package io.oryxos.channel.weixin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 入站缓存的 context_token；回复必须带回。
 *
 * <p>按用户去重，但用户数随时间只增不减 —— 表得有上界，否则长期运行的进程会一直持有历史上 每一个来过消息的用户（同族的 ReplySessionStore
 * 也都带淘汰）。超出时丢掉最早的条目：那份 token 对应的回复早已发完，再需要时用户重发一条即可（{@link #require} 的报错就是这么说的）。
 *
 * <p>只按容量淘汰，不设 TTL —— 平台侧这份 token 的有效期没有可依据的口径，与其编一个时长， 不如把内存这件事管住。
 */
final class WeixinContextTokenStore {

  /** 最多记住多少个用户的上下文令牌。 */
  static final int MAX_TRACKED_USERS = 1000;

  private final Map<String, String> byUserId = Collections.synchronizedMap(new BoundedTokens());

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

  /** 只保留最近 {@link #MAX_TRACKED_USERS} 个用户的令牌。 */
  private static final class BoundedTokens extends LinkedHashMap<String, String> {

    private static final long serialVersionUID = 1L;

    BoundedTokens() {
      super(16, 0.75f, false);
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
      return size() > MAX_TRACKED_USERS;
    }
  }
}
