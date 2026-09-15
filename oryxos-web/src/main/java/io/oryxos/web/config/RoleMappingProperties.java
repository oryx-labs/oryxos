package io.oryxos.web.config;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 授权默认角色配置（039-identity-authorization）。
 *
 * <p>角色已落库后，未自带角色的主体靠本配置兜底。安全默认值：
 *
 * <ul>
 *   <li>{@code roles.default-user-roles} 默认<b>空</b>——无角色即拒绝；靠 {@link
 *       io.oryxos.web.security.RbacStartupCheck} 在启用 RBAC 时强制至少一个 ADMIN，防治理面锁死。
 *   <li>{@code roles.default-api-key-roles} 默认<b>空</b>——机器凭证不给默认权限。这也是与「API Key 上限不含成员与策略
 *       管理」一致的保守口径。
 * </ul>
 *
 * <p>前缀刻意用 {@code oryxos.web.rbac.roles} 而不是 {@code oryxos.web.rbac}：后者已被 {@link WebRbacProperties}
 * 占用，两个 {@code @ConfigurationProperties} 绑定同一前缀会在启动时冲突。
 */
@ConfigurationProperties(prefix = "oryxos.web.rbac.roles")
public class RoleMappingProperties {

  /** 管理台账号未自带角色时的默认角色。角色已落库后空默认档；无角色即拒绝。 */
  private Set<String> defaultUserRoles = new LinkedHashSet<>();

  /** API Key 未自带角色时的默认角色。默认空 = 拒绝（机器凭证不给默认权限）。 */
  private Set<String> defaultApiKeyRoles = new LinkedHashSet<>();

  /** 返回防御性拷贝，避免调用方改动内部集合（SpotBugs EI_EXPOSE_REP）。 */
  public Set<String> getDefaultUserRoles() {
    return Set.copyOf(defaultUserRoles);
  }

  public void setDefaultUserRoles(Set<String> defaultUserRoles) {
    this.defaultUserRoles =
        defaultUserRoles == null ? new LinkedHashSet<>() : new LinkedHashSet<>(defaultUserRoles);
  }

  /** 返回防御性拷贝，避免调用方改动内部集合（SpotBugs EI_EXPOSE_REP）。 */
  public Set<String> getDefaultApiKeyRoles() {
    return Set.copyOf(defaultApiKeyRoles);
  }

  public void setDefaultApiKeyRoles(Set<String> defaultApiKeyRoles) {
    this.defaultApiKeyRoles =
        defaultApiKeyRoles == null
            ? new LinkedHashSet<>()
            : new LinkedHashSet<>(defaultApiKeyRoles);
  }
}
