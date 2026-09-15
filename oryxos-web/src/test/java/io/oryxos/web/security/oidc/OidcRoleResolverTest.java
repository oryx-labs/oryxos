package io.oryxos.web.security.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.JWTClaimsSet;
import io.oryxos.core.auth.Role;
import io.oryxos.web.config.WebOidcProperties;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 040 验收 harness：claim→角色映射钉死。守：未配置/未命中→空集（触发 R14 本地权威分支）、 数组与单串两种 claim 形态、未知角色名忽略降权、大小写不敏感。 */
class OidcRoleResolverTest {

  private static OidcRoleResolver resolver(String claim, Map<String, String> mappings) {
    WebOidcProperties props = new WebOidcProperties();
    props.setRolesClaim(claim);
    props.setRoleMappings(mappings);
    return new OidcRoleResolver(props);
  }

  @Test
  @DisplayName("数组claim_多值命中并集")
  void arrayClaim_multipleHits() {
    OidcRoleResolver resolver =
        resolver("groups", Map.of("oryxos-admins", "ADMIN", "oryxos-editors", "editor"));
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .claim("groups", List.of("oryxos-admins", "oryxos-editors", "unrelated"))
            .build();
    assertThat(resolver.rolesFrom(claims)).containsExactlyInAnyOrder(Role.ADMIN, Role.EDITOR);
  }

  @Test
  @DisplayName("单串claim形态兼容")
  void singleStringClaim() {
    OidcRoleResolver resolver = resolver("role", Map.of("admin", "ADMIN"));
    JWTClaimsSet claims = new JWTClaimsSet.Builder().claim("role", "admin").build();
    assertThat(resolver.rolesFrom(claims)).containsExactly(Role.ADMIN);
  }

  @Test
  @DisplayName("未配置rolesClaim或映射表_恒空集")
  void notConfigured_empty() {
    JWTClaimsSet claims = new JWTClaimsSet.Builder().claim("groups", List.of("x")).build();
    assertThat(resolver(null, Map.of("x", "ADMIN")).rolesFrom(claims)).isEmpty();
    assertThat(resolver("groups", Map.of()).rolesFrom(claims)).isEmpty();
  }

  @Test
  @DisplayName("claim缺失或无命中_空集")
  void missingOrNoHit_empty() {
    OidcRoleResolver resolver = resolver("groups", Map.of("oryxos-admins", "ADMIN"));
    assertThat(resolver.rolesFrom(new JWTClaimsSet.Builder().build())).isEmpty();
    JWTClaimsSet noHit = new JWTClaimsSet.Builder().claim("groups", List.of("other")).build();
    assertThat(resolver.rolesFrom(noHit)).isEmpty();
  }

  @Test
  @DisplayName("未知角色名_WARN忽略不兜底")
  void unknownRoleName_ignored() {
    OidcRoleResolver resolver = resolver("groups", Map.of("g1", "SUPERUSER", "g2", "VIEWER"));
    JWTClaimsSet claims = new JWTClaimsSet.Builder().claim("groups", List.of("g1", "g2")).build();
    assertThat(resolver.rolesFrom(claims)).containsExactly(Role.VIEWER);
  }

  @Test
  @DisplayName("provisionDefaults_解析配置默认档_未知值忽略")
  void provisionDefaults() {
    WebOidcProperties props = new WebOidcProperties();
    props.setProvisionDefaultRoles(Set.of("viewer", "BOGUS"));
    assertThat(new OidcRoleResolver(props).provisionDefaults()).containsExactly(Role.VIEWER);
  }
}
