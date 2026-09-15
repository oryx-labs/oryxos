package io.oryxos.web.config;

import io.oryxos.core.auth.Role;
import io.oryxos.core.policy.AuthorizationService;
import io.oryxos.core.policy.RoleBasedAuthorizationServiceImpl;
import io.oryxos.web.security.RbacEnforcer;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 授权装配（039-identity-authorization）：决定容器里注入的是「全允」还是「按角色裁决」。
 *
 * <p>为什么默认必须是 {@link AuthorizationService#ALLOW_ALL}：未启用授权时行为要与引入本层之前逐字节一致 （018 SC-001）。把它做成 Bean
 * 而不是在各调用点判 flag，是为了让「有没有授权层」只有一个事实来源—— 调用点只调 {@code decide}，不需要知道开关状态，也就不可能某个调用点漏判。
 *
 * <p>启用授权时，未自带角色的主体回落到 {@link RoleMappingProperties} 配置默认值：管理台账号与 API Key 的 {@code default-*-roles}
 * 均默认<b>空</b>（无角色即拒绝）。角色已落库后靠账号自身 roles + {@link io.oryxos.web.security.RbacStartupCheck} 保证至少一个
 * ADMIN，避免治理面锁死。
 */
@Configuration
@EnableConfigurationProperties({WebRbacProperties.class, RoleMappingProperties.class})
public class AuthorizationConfig {

  private static final Logger LOG = LoggerFactory.getLogger(AuthorizationConfig.class);

  private static final String LOG_ALLOW_ALL = "RBAC 未启用：授权决策点注入全允实现（行为与引入授权层前一致）";

  private static final String LOG_ROLE_BASED =
      "RBAC 已启用：授权决策点注入角色矩阵实现（userRoles={}, apiKeyRoles={}, denyAnonymous={}）";

  /**
   * 授权决策点。
   *
   * @param properties 授权开关与默认拒绝策略
   * @param roleProperties 默认角色配置
   */
  @Bean
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "日志仅记录枚举 Role 集合与 boolean 开关；角色名来自配置绑定后经 parseRoles 归一为 Role 枚举，"
              + "无法携带 CR/LF（镜像 ApiKeyService 的 SuppressFBWarnings 模式）。")
  AuthorizationService authorizationService(
      WebRbacProperties properties, RoleMappingProperties roleProperties) {
    if (!properties.isEnabled()) {
      LOG.info(LOG_ALLOW_ALL);
      return AuthorizationService.ALLOW_ALL;
    }
    Set<Role> userRoles = parseRoles(roleProperties.getDefaultUserRoles());
    Set<Role> apiKeyRoles = parseRoles(roleProperties.getDefaultApiKeyRoles());
    LOG.info(LOG_ROLE_BASED, userRoles, apiKeyRoles, properties.isDenyAnonymous());
    return new RoleBasedAuthorizationServiceImpl(userRoles, apiKeyRoles);
  }

  /**
   * RBAC 强制点：由认证门（{@code ApiKeyAuthFilter}）持有，授权只有一处实现。
   *
   * <p>本刀只接在 {@code /api/v1|v2/*}、{@code /actuator/*} 这条既有门上（作用域说明见 {@link RbacEnforcer}）。
   */
  @Bean
  RbacEnforcer rbacEnforcer(
      AuthorizationService authorizationService,
      WebRbacProperties properties,
      io.oryxos.storage.AuthzEventRecorder authzEventRecorder) {
    return new RbacEnforcer(authorizationService, properties, authzEventRecorder);
  }

  /** 角色名解析：大小写不敏感、去空白；无法识别的角色名直接忽略并告警，不阻断启动（配置写错不应导致服务起不来）。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "告警日志中的角色名来自部署方 YAML/环境变量配置（非请求体）；非法名仅用于启动诊断，"
              + "且随后被忽略不进入授权矩阵（镜像既有 CRLF_INJECTION_LOGS 落案模式）。")
  private static Set<Role> parseRoles(Set<String> raw) {
    Set<Role> parsed = new LinkedHashSet<>();
    if (raw == null) {
      return parsed;
    }
    for (String name : raw) {
      if (name == null || name.isBlank()) {
        continue;
      }
      try {
        parsed.add(Role.valueOf(name.strip().toUpperCase(java.util.Locale.ROOT)));
      } catch (IllegalArgumentException ex) {
        LOG.warn("忽略无法识别的角色名：{}（可选值 VIEWER/EDITOR/ADMIN）", name);
      }
    }
    return parsed;
  }
}
