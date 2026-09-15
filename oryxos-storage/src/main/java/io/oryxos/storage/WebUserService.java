package io.oryxos.storage;

import io.oryxos.core.auth.Role;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 管理台账号管理（012-web-auth）：创建/删除/改密/禁用/列出/校验。
 *
 * <p>密码哈希走 {@link PasswordEncoder}（由 oryxos-web 的 PasswordEncoderFactory 提供的
 * DelegatingPasswordEncoder，{@code {bcrypt}} 前缀，将来升 Argon2 无迁移）。明文密码 NEVER 落库/日志（宪法 VI）。
 *
 * <p>plain class（非 @Service），构造注入 repository + encoder，由 {@code OryxOsRuntime} @Bean 装配。 镜像 storage
 * 模块既有 {@code Jpa*Manager}/{@code Jpa*Store} 风格。只依赖 {@link PasswordEncoder}
 * 接口（spring-security-crypto，storage pom 已加），不引 oryxos-web 类（避免 storage→web 反向依赖）。
 *
 * <p>039 第二刀：{@link #rolesOf}/{@link #setRoles} 是角色唯一读写入口——每请求重解析、不缓存，撤权即时生效。
 */
public class WebUserService {

  private static final Logger LOG = LoggerFactory.getLogger(WebUserService.class);

  private static final int MIN_PASSWORD_LENGTH = 8;
  private static final int MAX_USERNAME_LENGTH = 64;

  /** 角色字段序列化分隔符（DB 存 CSV：VIEWER,EDITOR）。 */
  private static final String ROLES_DELIMITER = ",";

  /** 新建账号的安全默认档：只读；要干活须显式 {@code oryxos user role}。 */
  private static final String DEFAULT_ROLES_SERIALIZED = Role.VIEWER.name();

  private final WebUserRepository repository;
  private final PasswordEncoder passwordEncoder;

  public WebUserService(WebUserRepository repository, PasswordEncoder passwordEncoder) {
    this.repository = repository;
    this.passwordEncoder = passwordEncoder;
  }

  /** 创建账号；哈希密码后落库。重名/弱密码/用户名非法抛 IllegalArgumentException。默认角色 VIEWER。 */
  public WebUser create(String username, String rawPassword) {
    validateUsername(username);
    validatePassword(rawPassword);
    if (repository.existsByUsername(username)) {
      throw new IllegalArgumentException("user '" + username + "' already exists");
    }
    WebUser user = new WebUser();
    user.setUsername(username.strip());
    user.setPasswordHash(passwordEncoder.encode(rawPassword));
    user.setEnabled(true);
    user.setRoles(DEFAULT_ROLES_SERIALIZED);
    return repository.save(user);
  }

  /** 删账号；不存在抛 IllegalArgumentException。 */
  public void delete(String username) {
    WebUser user = mustFind(username);
    repository.delete(user);
  }

  /** 改密码；用户不存在/弱密码抛 IllegalArgumentException。 */
  public void changePassword(String username, String rawPassword) {
    validatePassword(rawPassword);
    WebUser user = mustFind(username);
    user.setPasswordHash(passwordEncoder.encode(rawPassword));
    user.setUpdatedAt(Instant.now());
    repository.save(user);
  }

  /** 禁用账号；不存在抛 IllegalArgumentException。 */
  public void disable(String username) {
    WebUser user = mustFind(username);
    user.setEnabled(false);
    user.setUpdatedAt(Instant.now());
    repository.save(user);
  }

  /** 启用账号（对称 disable）；不存在抛 IllegalArgumentException。 */
  public void enable(String username) {
    WebUser user = mustFind(username);
    user.setEnabled(true);
    user.setUpdatedAt(Instant.now());
    repository.save(user);
  }

  /** 列全部账号（按 username 排序）；仅返回实体（list 命令负责不显 hash）。 */
  public List<WebUser> list() {
    return repository.findAll().stream()
        .sorted(Comparator.comparing(WebUser::getUsername))
        .toList();
  }

  /** 校验账密；用户不存在/密码错/禁用均返 false（不区分原因，防用户名枚举）。 */
  public boolean verify(String username, String rawPassword) {
    if (username == null || rawPassword == null) {
      return false;
    }
    return repository
        .findByUsername(username)
        .filter(WebUser::isEnabled)
        .map(user -> passwordEncoder.matches(rawPassword, user.getPasswordHash()))
        .orElse(false);
  }

  /** 启动校验用：是否存在 enabled 账号（FR-006，auth.enabled=true 但无 enabled 账号阻断启动）。 */
  public boolean hasEnabledAccount() {
    return repository.findAll().stream().anyMatch(WebUser::isEnabled);
  }

  /** OIDC 映射后验启用态；不存在/禁用均 false（与 verify 同防枚举口径）。 */
  public boolean isEnabledUser(String username) {
    if (username == null || username.isBlank()) {
      return false;
    }
    return repository.findByUsername(username.strip()).filter(WebUser::isEnabled).isPresent();
  }

  /**
   * 每请求解析账号角色（039）。
   *
   * <ul>
   *   <li>账号不存在 → 空集（不授权）
   *   <li>未知 token → WARN 后忽略（降权，绝不向上兜底）
   *   <li>空串 / 解析结果为空 → 空集（拒绝，不是默认档）
   * </ul>
   */
  public Set<Role> rolesOf(String username) {
    if (username == null || username.isBlank()) {
      return Set.of();
    }
    return repository
        .findByUsername(username.strip())
        .map(user -> parseRoles(user.getRoles()))
        .orElse(Set.of());
  }

  /** 规范化写入角色；按 VIEWER,EDITOR,ADMIN 顺序序列化。空集写空串（= 不授权）。账号不存在抛 IllegalArgumentException。 */
  public void setRoles(String username, Set<Role> roles) {
    WebUser user = mustFind(username);
    user.setRoles(serializeRoles(roles));
    user.setUpdatedAt(Instant.now());
    repository.save(user);
  }

  /** 启动校验：是否存在至少一个 ADMIN 账号（rbac 启用时防治理面锁死）。 */
  public boolean hasAdminAccount() {
    return repository.findAll().stream()
        .filter(WebUser::isEnabled)
        .anyMatch(user -> parseRoles(user.getRoles()).contains(Role.ADMIN));
  }

  private WebUser mustFind(String username) {
    return repository
        .findByUsername(username)
        .orElseThrow(() -> new IllegalArgumentException("user '" + username + "' not found"));
  }

  private static void validateUsername(String username) {
    if (username == null || username.isBlank()) {
      throw new IllegalArgumentException("username must not be empty");
    }
    String trimmed = username.strip();
    if (trimmed.length() > MAX_USERNAME_LENGTH) {
      throw new IllegalArgumentException(
          "username must be at most " + MAX_USERNAME_LENGTH + " characters");
    }
    if (trimmed.chars().anyMatch(Character::isWhitespace)) {
      throw new IllegalArgumentException("username must not contain whitespace");
    }
  }

  private static void validatePassword(String rawPassword) {
    if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
      throw new IllegalArgumentException(
          "password must be at least " + MIN_PASSWORD_LENGTH + " characters");
    }
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "角色名来自库内配置字段；未知 token 仅记 WARN 后忽略，不进入授权矩阵。")
  static Set<Role> parseRoles(String raw) {
    if (raw == null || raw.isBlank()) {
      return Set.of();
    }
    Set<Role> parsed = EnumSet.noneOf(Role.class);
    for (String token : raw.split(ROLES_DELIMITER)) {
      if (token == null || token.isBlank()) {
        continue;
      }
      String name = token.strip().toUpperCase(Locale.ROOT);
      try {
        parsed.add(Role.valueOf(name));
      } catch (IllegalArgumentException ex) {
        LOG.warn("忽略无法识别的角色 token：{}（可选值 VIEWER/EDITOR/ADMIN）", name);
      }
    }
    return parsed.isEmpty() ? Set.of() : Set.copyOf(parsed);
  }

  /** 按 VIEWER → EDITOR → ADMIN 固定顺序输出，保证读写往返稳定。 */
  static String serializeRoles(Set<Role> roles) {
    if (roles == null || roles.isEmpty()) {
      return "";
    }
    Set<Role> ordered = new LinkedHashSet<>();
    for (Role role : List.of(Role.VIEWER, Role.EDITOR, Role.ADMIN)) {
      if (roles.contains(role)) {
        ordered.add(role);
      }
    }
    for (Role role : roles) {
      ordered.add(role);
    }
    StringBuilder sb = new StringBuilder();
    for (Role role : ordered) {
      if (sb.length() > 0) {
        sb.append(ROLES_DELIMITER);
      }
      sb.append(role.name());
    }
    return sb.toString();
  }
}
