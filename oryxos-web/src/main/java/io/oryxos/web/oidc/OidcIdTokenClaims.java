package io.oryxos.web.oidc;

/**
 * 经验证的 id_token 声明子集（040）。callback 只用 iss/sub（+ 可选 email）做映射，不做角色裁决。
 *
 * @param issuer iss
 * @param subject sub
 * @param email 可选 email claim
 */
public record OidcIdTokenClaims(String issuer, String subject, String email) {}
