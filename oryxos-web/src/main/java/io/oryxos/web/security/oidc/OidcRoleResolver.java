package io.oryxos.web.security.oidc;

import com.nimbusds.jwt.JWTClaimsSet;
import io.oryxos.core.auth.Role;
import io.oryxos.web.config.WebOidcProperties;
import java.text.ParseException;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * claim → OryxOS 角色映射（040 US2，R14 的 web 侧输入端）：从 ID Token 提取 roles-claim 值集合， 按 {@code
 * role-mappings} 配置映射为角色命中集。
 *
 * <p>命中集为空（未配置映射规则/claim 缺失/无命中）时，{@code OidcIdentityService} 走 R14「保留本地角色」分支。 未知角色名 WARN
 * 后忽略（降权不兜底，镜像 {@code WebUserService.parseRoles} 纪律）。
 */
public class OidcRoleResolver {

  private static final Logger LOG = LoggerFactory.getLogger(OidcRoleResolver.class);

  private final WebOidcProperties properties;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "properties 为 Spring 注入共享单例，存同一引用正是意图。")
  public OidcRoleResolver(WebOidcProperties properties) {
    this.properties = properties;
  }

  /** 从 ID Token claims 解析角色命中集；claim 缺失/类型异常/无命中一律空集（不抛，走本地权威分支）。 */
  public Set<Role> rolesFrom(JWTClaimsSet claims) {
    String claimName = properties.getRolesClaim();
    Map<String, String> mappings = properties.getRoleMappings();
    if (claimName == null || claimName.isBlank() || mappings.isEmpty() || claims == null) {
      return Set.of();
    }
    List<String> values = claimValues(claims, claimName);
    if (values.isEmpty()) {
      return Set.of();
    }
    Set<Role> hits = EnumSet.noneOf(Role.class);
    for (String value : values) {
      String mapped = mappings.get(value);
      if (mapped != null) {
        parseRole(mapped).ifPresent(hits::add);
      }
    }
    return hits.isEmpty() ? Set.of() : Set.copyOf(hits);
  }

  /** JIT 首登无命中时的默认供给角色（C1-A，配置 provision-default-roles）。 */
  public Set<Role> provisionDefaults() {
    Set<Role> parsed = EnumSet.noneOf(Role.class);
    for (String name : properties.getProvisionDefaultRoles()) {
      parseRole(name).ifPresent(parsed::add);
    }
    return parsed.isEmpty() ? Set.of() : Set.copyOf(parsed);
  }

  /** claim 值提取：兼容字符串数组与单字符串两种 IdP 形态；类型不符只 WARN 不抛。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "claimName 来自本地配置文件（roles-claim），非请求输入。")
  private static List<String> claimValues(JWTClaimsSet claims, String claimName) {
    try {
      List<String> list = claims.getStringListClaim(claimName);
      if (list != null) {
        return list;
      }
    } catch (ParseException ignored) {
      // 非数组形态，退回单字符串尝试。
    }
    try {
      String single = claims.getStringClaim(claimName);
      return single == null || single.isBlank() ? List.of() : List.of(single);
    } catch (ParseException ex) {
      LOG.warn("roles-claim {} 类型无法解析为字符串（数组），按无命中处理", claimName);
      return List.of();
    }
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "角色名来自本地配置文件；未知值仅 WARN 后忽略。")
  private static java.util.Optional<Role> parseRole(String name) {
    try {
      return java.util.Optional.of(Role.valueOf(name.strip().toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException ex) {
      LOG.warn("忽略无法识别的角色名：{}（可选值 VIEWER/EDITOR/ADMIN）", name);
      return java.util.Optional.empty();
    }
  }
}
