package io.oryxos.web.oidc;

import java.util.List;

/**
 * 经验证的 id_token 声明子集（040）。callback 用 iss/sub 做身份映射；{@code groups} 只供组→角色同步，不做 AuthorizationService
 * 裁决。
 *
 * @param issuer iss
 * @param subject sub
 * @param email 可选 email claim
 * @param groups 可选组声明；缺省空列表
 */
public record OidcIdTokenClaims(String issuer, String subject, String email, List<String> groups) {

  public OidcIdTokenClaims {
    groups = groups == null ? List.of() : List.copyOf(groups);
  }

  /** 既有三参调用点：无组声明。 */
  public OidcIdTokenClaims(String issuer, String subject, String email) {
    this(issuer, subject, email, List.of());
  }
}
