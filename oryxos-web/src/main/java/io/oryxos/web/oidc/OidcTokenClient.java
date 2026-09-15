package io.oryxos.web.oidc;

import io.oryxos.web.config.WebOidcProperties;

/**
 * IdP token 交换与 id_token 校验抽象（040）。
 *
 * <p>生产走 {@link HttpOidcTokenClient}（JWKS + Nimbus）；单测注入假实现，避免打真 IdP。
 */
public interface OidcTokenClient {

  /**
   * 用授权码 + PKCE verifier 换 token，并校验返回的 id_token（iss/aud/exp + 签名）。
   *
   * @param code 授权码
   * @param codeVerifier PKCE verifier
   * @param properties 当前 OIDC 配置（含 client 机密，实现内勿日志）
   * @return 经验证声明
   */
  OidcIdTokenClaims exchangeAndValidate(
      String code, String codeVerifier, WebOidcProperties properties);

  /** 解析授权端点（配置覆盖优先，否则 OIDC discovery）。 */
  String resolveAuthorizationEndpoint(WebOidcProperties properties);
}
