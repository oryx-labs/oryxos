package io.oryxos.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * 浏览器登录 session 管理（012-web-auth US3）：创建/查询有效 session/登出/过期判定。
 *
 * <p>session id 用 {@link UUID#randomUUID()}（不可预测）。过期时间 = 创建时间 + sessionTtl（由构造注入的 {@link
 * Duration}，从 {@code WebAuthProperties.sessionTtl} 读，但本类不引 oryxos-web 类避免 storage→web 反向依赖）。
 *
 * <p>惰性清过期行：{@link #findValid(String)} 查到过期行时顺手 {@code delete}（无后台定时线程）。
 *
 * <p>plain class（非 @Service），构造注入 repository + ttl，由 {@code OryxOsRuntime} @Bean 装配。镜像 {@code
 * WebUserService} 风格。只依赖 {@link Duration}（JDK）+ {@link WebSessionRepository}。
 */
public class WebSessionService {

  private final WebSessionRepository repository;
  private final Duration sessionTtl;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "repository 为 Spring 注入的共享单例，构造注入存同一引用正是意图（镜像既有 WebUserService / Controller 的 SuppressFBWarnings 模式）。")
  public WebSessionService(WebSessionRepository repository, Duration sessionTtl) {
    this.repository = repository;
    this.sessionTtl = sessionTtl;
  }

  /** 建 session。session id = UUID，expires_at = now + sessionTtl。 */
  @Transactional(rollbackFor = Exception.class)
  public WebSession create(String username) {
    WebSession session = new WebSession();
    session.setSessionId(UUID.randomUUID().toString());
    session.setUsername(username);
    Instant now = Instant.now();
    session.setExpiresAt(now.plus(sessionTtl));
    return repository.save(session);
  }

  /**
   * 查有效 session：命中且未过期返；过期行顺手 delete（惰性清）。不查 user enabled（Clarifications Q1 B： 改密/禁用后旧 session
   * 仍有效到过期）。
   */
  @Transactional(rollbackFor = Exception.class)
  public Optional<WebSession> findValid(String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      return Optional.empty();
    }
    Optional<WebSession> found = repository.findBySessionId(sessionId);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    WebSession session = found.get();
    if (isExpired(session)) {
      repository.deleteBySessionId(sessionId);
      return Optional.empty();
    }
    return Optional.of(session);
  }

  /** 登出：删 session 行。未登录（无 session）也幂等成功。 */
  @Transactional(rollbackFor = Exception.class)
  public void delete(String sessionId) {
    if (sessionId != null && !sessionId.isBlank()) {
      repository.deleteBySessionId(sessionId);
    }
  }

  public boolean isExpired(WebSession session) {
    return session.getExpiresAt() == null || session.getExpiresAt().isBefore(Instant.now());
  }
}
