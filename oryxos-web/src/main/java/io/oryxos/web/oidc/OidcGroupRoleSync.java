package io.oryxos.web.oidc;

import io.oryxos.core.auth.Role;
import io.oryxos.storage.WebUserService;
import io.oryxos.web.config.WebOidcProperties;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IdP 组 → 本地角色的身份同步（040 / #461）。
 *
 * <p>只在 {@code oryxos.web.oidc.group-roles} 非空、且至少一个组命中时，调用 {@link
 * WebUserService#setRoles}。未命中不改已有角色（避免一次缺 claim 把管理员锁死）。<b>不</b>调用 {@code
 * AuthorizationService}——后续请求仍走 session→Principal→既有决策点。
 */
public final class OidcGroupRoleSync {

  private static final Logger LOG = LoggerFactory.getLogger(OidcGroupRoleSync.class);

  private final WebUserService userService;

  public OidcGroupRoleSync(WebUserService userService) {
    this.userService = userService;
  }

  /**
   * @return 实际写入的角色；映射表为空或没有任何命中时为空（未写库）
   */
  public Optional<Set<Role>> apply(
      String username, List<String> groups, WebOidcProperties properties) {
    Map<String, String> configured = properties.getGroupRoles();
    if (configured.isEmpty()) {
      return Optional.empty();
    }
    Set<Role> matched = match(groups, configured);
    if (matched.isEmpty()) {
      return Optional.empty();
    }
    userService.setRoles(username, matched);
    return Optional.of(matched);
  }

  private static Set<Role> match(List<String> groups, Map<String, String> configured) {
    if (groups == null || groups.isEmpty()) {
      return Set.of();
    }
    Set<Role> matched = EnumSet.noneOf(Role.class);
    for (String group : groups) {
      if (group == null || group.isBlank()) {
        continue;
      }
      String mapped = configured.get(group.strip());
      Role role = parseRole(mapped);
      if (role != null) {
        matched.add(role);
      }
    }
    return matched.isEmpty() ? Set.of() : Set.copyOf(new LinkedHashSet<>(matched));
  }

  private static Role parseRole(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Role.valueOf(raw.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      LOG.warn("忽略无法识别的 OIDC 组角色配置");
      return null;
    }
  }
}
