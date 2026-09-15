package io.oryxos.storage;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * OIDC 授权流程临时状态管理（040 FR-008）：创建（生成 state/nonce/PKCE verifier）与单次消费。
 *
 * <p>state/nonce/verifier 均为 32 字节 SecureRandom 的 URL-safe Base64（43 字符，≥256bit 熵，不可预测）。 消费走 {@link
 * OidcAuthRequestRepository#consume} CAS——重放/过期/未知 state 一律拒绝。惰性清过期行 （镜像 {@link WebSessionService}
 * 的无后台线程模式）。
 *
 * <p>plain class（非 @Service），由 {@code OryxOsRuntime} @Bean 装配（镜像 WebSessionService）。
 */
public class OidcAuthRequestStore {

  /** 授权往返允许的最长时间：覆盖用户在 IdP 登录页的停留，超时视为放弃。 */
  static final Duration TTL = Duration.ofMinutes(10);

  private static final int RANDOM_BYTES = 32;

  /** state 列宽（V10 DDL VARCHAR(64)）：超宽输入必非我方签发，直接拒绝不查库。 */
  private static final int MAX_STATE_LENGTH = 64;

  private final OidcAuthRequestRepository repository;
  private final SecureRandom secureRandom = new SecureRandom();

  /** 一次登录发起产出的三元组；verifier 只在 code 换 token 时回传 IdP，绝不出现在浏览器侧。 */
  public record PendingAuth(String state, String nonce, String pkceVerifier) {}

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "repository 为 Spring 注入共享单例，存同一引用正是意图（镜像 WebSessionService）。")
  public OidcAuthRequestStore(OidcAuthRequestRepository repository) {
    this.repository = repository;
  }

  /** 发起登录：生成并落库一条待消费记录。顺手清过期行。 */
  @Transactional(rollbackFor = Exception.class)
  public PendingAuth create() {
    Instant now = Instant.now();
    repository.purgeExpired(now);
    OidcAuthRequestEntity entity = new OidcAuthRequestEntity();
    entity.setState(randomToken());
    entity.setNonce(randomToken());
    entity.setPkceVerifier(randomToken());
    entity.setExpiresAt(now.plus(TTL));
    repository.save(entity);
    return new PendingAuth(entity.getState(), entity.getNonce(), entity.getPkceVerifier());
  }

  /** 回调消费：CAS 置 consumed_at，成功返回 nonce/verifier。空返回 = 重放/过期/未知，调用方按 invalid_state 拒绝。 */
  @Transactional(rollbackFor = Exception.class)
  public Optional<PendingAuth> consume(String state) {
    if (state == null || state.isBlank() || state.length() > MAX_STATE_LENGTH) {
      return Optional.empty();
    }
    Instant now = Instant.now();
    if (repository.consume(state, now) != 1) {
      return Optional.empty();
    }
    return repository
        .findById(state)
        .map(e -> new PendingAuth(e.getState(), e.getNonce(), e.getPkceVerifier()));
  }

  private String randomToken() {
    byte[] bytes = new byte[RANDOM_BYTES];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
