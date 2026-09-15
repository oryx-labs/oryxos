package io.oryxos.web.security.oidc;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JWKS 缓存（040 R11/FR-009）：15 分钟正常缓存；未知 kid 触发限速强刷（同一 IdP 最小间隔 60 秒， 防伪造 kid 打成拉取风暴）——覆盖 IdP
 * 密钥轮换窗口。拉取失败沿用旧缓存（可用性优先），无缓存则抛 idp_unreachable（由 {@link OidcClient#fetchJwksJson} 上抛）。
 */
public class JwksCache {

  private static final Logger LOG = LoggerFactory.getLogger(JwksCache.class);

  static final Duration CACHE_TTL = Duration.ofMinutes(15);
  static final Duration MIN_REFRESH_INTERVAL = Duration.ofSeconds(60);

  private final OidcClient client;
  private final Object lock = new Object();

  private JWKSet cachedSet;
  private Instant fetchedAt;
  private Instant lastRefreshAttempt;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "client 为 Spring 注入共享单例，存同一引用正是意图。")
  public JwksCache(OidcClient client) {
    this.client = client;
  }

  /** 按 kid 取验签公钥：缓存命中直接返；缓存过期或 kid 未知时限速强刷。仍找不到返回 null （调用方按 invalid_signature 拒绝，绝不放行）。 */
  public JWK keyFor(String kid) {
    synchronized (lock) {
      Instant now = Instant.now();
      if (cachedSet != null && fetchedAt != null && fetchedAt.plus(CACHE_TTL).isAfter(now)) {
        JWK hit = lookup(kid);
        if (hit != null) {
          return hit;
        }
        // kid 未命中：可能是密钥轮换，限速强刷一次。
      }
      if (lastRefreshAttempt == null
          || lastRefreshAttempt.plus(MIN_REFRESH_INTERVAL).isBefore(now)
          || cachedSet == null) {
        refresh(now);
      }
      return lookup(kid);
    }
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "日志只含异常类名与错误分类枚举串，无外部可控内容。")
  private void refresh(Instant now) {
    lastRefreshAttempt = now;
    try {
      String json = client.fetchJwksJson();
      cachedSet = JWKSet.parse(json);
      fetchedAt = now;
    } catch (ParseException ex) {
      LOG.warn("JWKS 解析失败，沿用旧缓存：{}", ex.getClass().getSimpleName());
    } catch (OidcFlowException ex) {
      if (cachedSet == null) {
        throw ex;
      }
      LOG.warn("JWKS 刷新失败，沿用旧缓存：{}", ex.code().code());
    }
  }

  private JWK lookup(String kid) {
    if (cachedSet == null) {
      return null;
    }
    if (kid == null || kid.isBlank()) {
      // 无 kid 的单钥 IdP：JWKS 恰好一把钥匙时用之，多把则无法判定。
      return cachedSet.getKeys().size() == 1 ? cachedSet.getKeys().get(0) : null;
    }
    return cachedSet.getKeyByKeyId(kid);
  }
}
