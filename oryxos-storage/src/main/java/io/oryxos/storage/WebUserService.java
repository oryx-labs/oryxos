package io.oryxos.storage;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
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
 */
public class WebUserService {

  private static final int MIN_PASSWORD_LENGTH = 8;
  private static final int MAX_USERNAME_LENGTH = 64;

  private final WebUserRepository repository;
  private final PasswordEncoder passwordEncoder;

  public WebUserService(WebUserRepository repository, PasswordEncoder passwordEncoder) {
    this.repository = repository;
    this.passwordEncoder = passwordEncoder;
  }

  /** 创建账号；哈希密码后落库。重名/弱密码/用户名非法抛 IllegalArgumentException。 */
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
}
