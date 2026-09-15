package io.oryxos.storage;

import io.oryxos.core.auth.Role;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * 外部身份 → OryxOS 用户的映射与 JIT 供给（040 US2，C1-A/C3-C 拍板落点）。
 *
 * <p>登录成功后调 {@link #login}：{@code (iss, sub)} 命中 → 既有用户 + 条件权威角色刷新；未命中 → JIT 自动供给 （建用户 +
 * 映射行，同一事务）。角色刷新遵循 R14 行为表（research.md）：claim 命中集非空 → 覆写为命中集 （IdP 权威，撤组即降权）；命中集为空（未配置映射规则或未命中）→
 * 保留本地角色（本地权威）。
 *
 * <p>JIT 用户的密码 = 随机 256-bit 秘密的 bcrypt 哈希（生成即弃）：本地账密登录路径正常走且永不可能匹配—— OIDC 用户无法经 Basic Auth
 * 门进入，也不需要维护密码。
 *
 * <p>plain class（非 @Service），由 {@code OryxOsRuntime} @Bean 装配（镜像 WebUserService）。
 */
public class OidcIdentityService {

  private static final Logger LOG = LoggerFactory.getLogger(OidcIdentityService.class);

  private static final int MAX_SUFFIX_ATTEMPTS = 99;
  private static final int RANDOM_SECRET_BYTES = 32;

  private final OidcIdentityRepository identityRepository;
  private final WebUserRepository userRepository;
  private final WebUserService userService;
  private final SecureRandom secureRandom = new SecureRandom();

  /**
   * 一次 OIDC 登录的映射结果。
   *
   * @param username 映射/供给出的 OryxOS 用户名
   * @param roles 本次登录生效的角色集
   * @param firstLogin 是否 JIT 首登（调用方据此落 mapping_created 事件）
   * @param rolesChanged 角色是否被本次登录刷新改变（调用方据此落 roles_changed 事件）
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
      justification = "roles 在紧凑构造器经 Set.copyOf 冻结为不可变集合，存/返同一引用安全。")
  public record OidcLoginResult(
      String username, Set<Role> roles, boolean firstLogin, boolean rolesChanged) {

    public OidcLoginResult {
      roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "repository/service 均为 Spring 注入共享单例，存同一引用正是意图（镜像 WebUserService 装配模式）。")
  public OidcIdentityService(
      OidcIdentityRepository identityRepository,
      WebUserRepository userRepository,
      WebUserService userService) {
    this.identityRepository = identityRepository;
    this.userRepository = userRepository;
    this.userService = userService;
  }

  /**
   * 映射或供给：ID Token 验证通过后的唯一入口。
   *
   * @param issuer ID Token iss 原文
   * @param subject ID Token sub（稳定锚点）
   * @param email 展示用邮箱（可空）
   * @param usernameCandidate 首登用户名派生候选（已规范化；冲突时本方法追加后缀）
   * @param claimRoles claim 映射命中集（空集 = 未配置映射规则或未命中，触发 R14「保留本地」分支）
   * @param provisionDefaultRoles JIT 首登无命中时的默认供给角色
   */
  @Transactional(rollbackFor = Exception.class)
  public OidcLoginResult login(
      String issuer,
      String subject,
      String email,
      String usernameCandidate,
      Set<Role> claimRoles,
      Set<Role> provisionDefaultRoles) {
    Instant now = Instant.now();
    Optional<OidcIdentity> existing = identityRepository.findByIssuerAndSubject(issuer, subject);
    if (existing.isPresent()) {
      return refreshExisting(existing.get(), email, claimRoles, provisionDefaultRoles, now);
    }
    return provision(
        issuer, subject, email, usernameCandidate, claimRoles, provisionDefaultRoles, now);
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "username 来自本库 oidc_identities（首登时经 OidcUsernameDeriver 限定 [a-z0-9._-] 字符集），无换行注入面。")
  private OidcLoginResult refreshExisting(
      OidcIdentity identity,
      String email,
      Set<Role> claimRoles,
      Set<Role> provisionDefaultRoles,
      Instant now) {
    identity.setLastLoginAt(now);
    if (email != null && !email.isBlank()) {
      identity.setEmail(email);
    }
    identityRepository.save(identity);
    String username = identity.getUsername();
    if (userRepository.findByUsername(username).isEmpty()) {
      // 防御分支：管理员删了用户但映射行还在——按首登规则重供给同名用户，映射锚点不变。
      LOG.warn("OIDC 映射用户 {} 已被删除，按首登规则重新供给", username);
      Set<Role> roles = claimRoles.isEmpty() ? provisionDefaultRoles : claimRoles;
      createUser(username, roles);
      return new OidcLoginResult(username, roles, true, false);
    }
    Set<Role> localRoles = userService.rolesOf(username);
    if (claimRoles.isEmpty()) {
      // R14：未配置映射规则/未命中 → 本地权威，保留本地角色。
      return new OidcLoginResult(username, localRoles, false, false);
    }
    if (claimRoles.equals(localRoles)) {
      return new OidcLoginResult(username, localRoles, false, false);
    }
    // R14：命中 → IdP 权威，覆写本地（撤组即降权）。
    userService.setRoles(username, claimRoles);
    return new OidcLoginResult(username, claimRoles, false, true);
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "username 经 OidcUsernameDeriver + WebUserService 校验限定 [a-z0-9._-] 无空白字符；roles 为枚举集。")
  private OidcLoginResult provision(
      String issuer,
      String subject,
      String email,
      String usernameCandidate,
      Set<Role> claimRoles,
      Set<Role> provisionDefaultRoles,
      Instant now) {
    String username = uniqueUsername(usernameCandidate);
    Set<Role> roles = claimRoles.isEmpty() ? provisionDefaultRoles : claimRoles;
    createUser(username, roles);
    OidcIdentity identity = new OidcIdentity();
    identity.setIssuer(issuer);
    identity.setSubject(subject);
    identity.setUsername(username);
    if (email != null && !email.isBlank()) {
      identity.setEmail(email);
    }
    identity.setFirstLoginAt(now);
    identity.setLastLoginAt(now);
    identityRepository.save(identity);
    LOG.info("JIT 供给 OIDC 用户 {}（角色 {}）", username, roles);
    return new OidcLoginResult(username, roles, true, false);
  }

  private void createUser(String username, Set<Role> roles) {
    // 随机秘密生成后即弃：verify() 永不匹配，OIDC 用户不走本地账密门（research R5）。
    byte[] secret = new byte[RANDOM_SECRET_BYTES];
    secureRandom.nextBytes(secret);
    userService.create(username, Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
    userService.setRoles(username, roles);
  }

  private String uniqueUsername(String candidate) {
    if (!userRepository.existsByUsername(candidate)) {
      return candidate;
    }
    for (int i = 2; i <= MAX_SUFFIX_ATTEMPTS; i++) {
      String next = candidate + "-" + i;
      if (!userRepository.existsByUsername(next)) {
        return next;
      }
    }
    throw new IllegalStateException("无法为 OIDC 用户派生唯一用户名：" + candidate);
  }
}
