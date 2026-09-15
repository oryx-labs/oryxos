package io.oryxos.web.oidc;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** OIDC 登录 state→PKCE 暂存（040）。ConcurrentHashMap + TTL（默认 10 分钟）；无分布式要求（单实例 first cut）。 */
public class OidcPendingStore {

  private final ConcurrentHashMap<String, OidcPendingLogin> pending = new ConcurrentHashMap<>();
  private final Duration ttl;

  public OidcPendingStore(Duration ttl) {
    this.ttl = ttl == null ? Duration.ofMinutes(10) : ttl;
  }

  public void put(String state, String codeVerifier) {
    pending.put(state, new OidcPendingLogin(codeVerifier, Instant.now()));
    evictExpired();
  }

  /** 取出并移除；过期或不存在返 empty。 */
  public Optional<OidcPendingLogin> take(String state) {
    if (state == null || state.isBlank()) {
      return Optional.empty();
    }
    OidcPendingLogin entry = pending.remove(state);
    if (entry == null) {
      return Optional.empty();
    }
    if (entry.createdAt().plus(ttl).isBefore(Instant.now())) {
      return Optional.empty();
    }
    return Optional.of(entry);
  }

  private void evictExpired() {
    Instant cutoff = Instant.now().minus(ttl);
    Iterator<Map.Entry<String, OidcPendingLogin>> it = pending.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<String, OidcPendingLogin> entry = it.next();
      if (entry.getValue().createdAt().isBefore(cutoff)) {
        it.remove();
      }
    }
  }
}
