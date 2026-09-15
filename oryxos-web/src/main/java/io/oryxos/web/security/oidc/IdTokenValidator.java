package io.oryxos.web.security.oidc;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.oryxos.web.config.WebOidcProperties;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;

/**
 * ID Token 验证（040 FR-001/FR-009）：JWS 签名（JWKS 公钥，RSA/EC 走 JDK JCA）+ 标准 claims 校验 （iss 精确匹配、aud 含
 * client_id、exp/iat 带时钟偏移窗、nonce 与本次授权请求匹配）。
 *
 * <p>任何失败抛 {@link OidcFlowException}（分类级 message）；令牌内容 NEVER 进入异常与日志（SC-006）。
 */
public class IdTokenValidator {

  private static final String SLASH = "/";

  private final WebOidcProperties properties;
  private final JwksCache jwksCache;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "properties/jwksCache 为 Spring 注入共享单例，存同一引用正是意图。")
  public IdTokenValidator(WebOidcProperties properties, JwksCache jwksCache) {
    this.properties = properties;
    this.jwksCache = jwksCache;
  }

  /** 验签 + claims 校验，通过返回 claims。 */
  public JWTClaimsSet validate(String idToken, String expectedNonce) {
    SignedJWT jwt = parse(idToken);
    verifySignature(jwt);
    JWTClaimsSet claims = claimsOf(jwt);
    verifyClaims(claims, expectedNonce);
    return claims;
  }

  private static SignedJWT parse(String idToken) {
    try {
      return SignedJWT.parse(idToken);
    } catch (ParseException ex) {
      throw new OidcFlowException(OidcErrorCode.INVALID_SIGNATURE, "ID Token 无法解析");
    }
  }

  private void verifySignature(SignedJWT jwt) {
    String kid = jwt.getHeader().getKeyID();
    JWK jwk = jwksCache.keyFor(kid);
    if (jwk == null) {
      throw new OidcFlowException(OidcErrorCode.INVALID_SIGNATURE, "JWKS 中无匹配公钥");
    }
    boolean valid;
    try {
      valid = jwt.verify(verifierFor(jwk));
    } catch (JOSEException ex) {
      throw new OidcFlowException(OidcErrorCode.INVALID_SIGNATURE, "签名验证异常", ex);
    }
    if (!valid) {
      throw new OidcFlowException(OidcErrorCode.INVALID_SIGNATURE, "签名验证失败");
    }
  }

  private static JWSVerifier verifierFor(JWK jwk) throws JOSEException {
    if (jwk instanceof RSAKey rsaKey) {
      return new RSASSAVerifier(rsaKey.toRSAPublicKey());
    }
    if (jwk instanceof ECKey ecKey) {
      return new ECDSAVerifier(ecKey.toECPublicKey());
    }
    throw new OidcFlowException(OidcErrorCode.INVALID_SIGNATURE, "不支持的公钥类型");
  }

  private static JWTClaimsSet claimsOf(SignedJWT jwt) {
    try {
      return jwt.getJWTClaimsSet();
    } catch (ParseException ex) {
      throw new OidcFlowException(OidcErrorCode.INVALID_CLAIMS, "claims 无法解析");
    }
  }

  private void verifyClaims(JWTClaimsSet claims, String expectedNonce) {
    String expectedIssuer = strip(properties.getIssuer());
    if (claims.getIssuer() == null || !strip(claims.getIssuer()).equals(expectedIssuer)) {
      throw new OidcFlowException(OidcErrorCode.INVALID_CLAIMS, "iss 不匹配");
    }
    if (claims.getAudience() == null || !claims.getAudience().contains(properties.getClientId())) {
      throw new OidcFlowException(OidcErrorCode.INVALID_CLAIMS, "aud 不含 client_id");
    }
    Instant now = Instant.now();
    Date exp = claims.getExpirationTime();
    if (exp == null || exp.toInstant().plus(properties.getClockSkew()).isBefore(now)) {
      throw new OidcFlowException(OidcErrorCode.EXPIRED_TOKEN, "ID Token 已过期");
    }
    Date iat = claims.getIssueTime();
    if (iat != null && iat.toInstant().minus(properties.getClockSkew()).isAfter(now)) {
      throw new OidcFlowException(OidcErrorCode.INVALID_CLAIMS, "iat 在未来");
    }
    if (claims.getSubject() == null || claims.getSubject().isBlank()) {
      throw new OidcFlowException(OidcErrorCode.INVALID_CLAIMS, "缺少 sub");
    }
    String nonce;
    try {
      nonce = claims.getStringClaim("nonce");
    } catch (ParseException ex) {
      throw new OidcFlowException(OidcErrorCode.INVALID_CLAIMS, "nonce 无法解析");
    }
    if (nonce == null || !nonce.equals(expectedNonce)) {
      throw new OidcFlowException(OidcErrorCode.INVALID_CLAIMS, "nonce 不匹配");
    }
  }

  private static String strip(String value) {
    String v = value == null ? "" : value.strip();
    while (v.endsWith(SLASH)) {
      v = v.substring(0, v.length() - 1);
    }
    return v;
  }
}
