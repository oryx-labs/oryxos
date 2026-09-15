package io.oryxos.storage;

import java.time.Instant;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * OIDC 身份映射管理（040）：按 issuer+subject 查 / upsert / delete。
 *
 * <p>映射变更走 {@link AuthEventRecorder#recordOrThrow}——审计写失败则整笔事务失败（fail-closed）。 plain class，由 {@code
 * OryxOsRuntime} @Bean 装配。
 */
public class IdentityMappingService {

  private final IdentityMappingRepository repository;
  private final WebUserRepository userRepository;
  private final AuthEventRecorder authEventRecorder;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "repository/userRepository/recorder 均为 Spring 注入共享单例，存同一引用正是意图。")
  public IdentityMappingService(
      IdentityMappingRepository repository,
      WebUserRepository userRepository,
      AuthEventRecorder authEventRecorder) {
    this.repository = repository;
    this.userRepository = userRepository;
    this.authEventRecorder = authEventRecorder;
  }

  public Optional<IdentityMapping> findByIssuerAndSubject(String issuer, String subject) {
    if (issuer == null || subject == null || issuer.isBlank() || subject.isBlank()) {
      return Optional.empty();
    }
    return repository.findByIssuerAndSubject(issuer.strip(), subject.strip());
  }

  /**
   * 插入或更新映射。本地用户必须已存在（无 JIT）。审计失败则抛出并回滚。
   *
   * @return 落库后的映射行
   */
  @Transactional(rollbackFor = Exception.class)
  public IdentityMapping upsert(String issuer, String subject, String username, String email) {
    requireNonBlank(issuer, "issuer");
    requireNonBlank(subject, "subject");
    requireNonBlank(username, "username");
    String cleanIssuer = issuer.strip();
    String cleanSubject = subject.strip();
    String cleanUsername = username.strip();
    if (!userRepository.existsByUsername(cleanUsername)) {
      throw new IllegalArgumentException("user '" + cleanUsername + "' not found");
    }
    IdentityMapping mapping =
        repository
            .findByIssuerAndSubject(cleanIssuer, cleanSubject)
            .orElseGet(IdentityMapping::new);
    mapping.setIssuer(cleanIssuer);
    mapping.setSubject(cleanSubject);
    mapping.setUsername(cleanUsername);
    mapping.setEmail(email == null || email.isBlank() ? null : email.strip());
    mapping.setUpdatedAt(Instant.now());
    IdentityMapping saved = repository.save(mapping);
    authEventRecorder.recordOrThrow(
        AuthEventType.MAPPING_UPSERT,
        cleanUsername,
        "issuer=" + cleanIssuer + " subject=" + cleanSubject);
    return saved;
  }

  /** 删除映射；不存在幂等成功。审计失败则抛出。 */
  @Transactional(rollbackFor = Exception.class)
  public void delete(String issuer, String subject) {
    requireNonBlank(issuer, "issuer");
    requireNonBlank(subject, "subject");
    String cleanIssuer = issuer.strip();
    String cleanSubject = subject.strip();
    Optional<IdentityMapping> existing =
        repository.findByIssuerAndSubject(cleanIssuer, cleanSubject);
    if (existing.isEmpty()) {
      return;
    }
    String username = existing.get().getUsername();
    repository.deleteByIssuerAndSubject(cleanIssuer, cleanSubject);
    authEventRecorder.recordOrThrow(
        AuthEventType.MAPPING_DELETE,
        username,
        "issuer=" + cleanIssuer + " subject=" + cleanSubject);
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be empty");
    }
  }
}
