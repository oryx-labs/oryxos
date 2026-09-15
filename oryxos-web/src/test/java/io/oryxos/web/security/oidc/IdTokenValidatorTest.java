package io.oryxos.web.security.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.oryxos.web.config.WebOidcProperties;
import java.time.Instant;
import java.util.Date;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 040 验收 harness：IdTokenValidator——五类失败路径 + 时钟偏移窗边界钉死（真实 RSA 签名，mock JwksCache）。
 * 守：好令牌过、错签名/错iss/错aud/错nonce/过期各自分类拒绝、偏移窗内的过期仍放行。
 */
class IdTokenValidatorTest {

  private static final String ISSUER = "https://idp.example.com/realm";
  private static final String CLIENT_ID = "oryxos-admin";
  private static final String NONCE = "nonce-123";

  private static RSAKey goodKey;
  private static RSAKey rogueKey;

  private JwksCache jwksCache;
  private IdTokenValidator validator;

  @BeforeAll
  static void generateKeys() throws Exception {
    goodKey = new RSAKeyGenerator(2048).keyID("good-kid").generate();
    rogueKey = new RSAKeyGenerator(2048).keyID("good-kid").generate();
  }

  @BeforeEach
  void setUp() {
    WebOidcProperties props = new WebOidcProperties();
    props.setIssuer(ISSUER);
    props.setClientId(CLIENT_ID);
    jwksCache = mock(JwksCache.class);
    when(jwksCache.keyFor(any())).thenReturn(goodKey.toPublicJWK());
    validator = new IdTokenValidator(props, jwksCache);
  }

  @Test
  @DisplayName("合法令牌_验签与claims全过_返回claims")
  void validToken_passes() throws Exception {
    String token = sign(goodKey, claims(c -> {}));
    JWTClaimsSet result = validator.validate(token, NONCE);
    assertThat(result.getSubject()).isEqualTo("sub-1");
  }

  @Test
  @DisplayName("异钥签名_invalid_signature")
  void wrongKeySignature_rejected() throws Exception {
    String token = sign(rogueKey, claims(c -> {}));
    assertFailsWith(token, NONCE, OidcErrorCode.INVALID_SIGNATURE);
  }

  @Test
  @DisplayName("JWKS无匹配公钥_invalid_signature")
  void unknownKid_rejected() throws Exception {
    when(jwksCache.keyFor(any())).thenReturn(null);
    String token = sign(goodKey, claims(c -> {}));
    assertFailsWith(token, NONCE, OidcErrorCode.INVALID_SIGNATURE);
  }

  @Test
  @DisplayName("非JWT字符串_invalid_signature")
  void garbage_rejected() {
    assertFailsWith("not-a-jwt", NONCE, OidcErrorCode.INVALID_SIGNATURE);
  }

  @Test
  @DisplayName("iss不匹配_invalid_claims")
  void wrongIssuer_rejected() throws Exception {
    String token = sign(goodKey, claims(c -> c.issuer("https://evil.example.com")));
    assertFailsWith(token, NONCE, OidcErrorCode.INVALID_CLAIMS);
  }

  @Test
  @DisplayName("aud不含client_id_invalid_claims")
  void wrongAudience_rejected() throws Exception {
    String token = sign(goodKey, claims(c -> c.audience("other-client")));
    assertFailsWith(token, NONCE, OidcErrorCode.INVALID_CLAIMS);
  }

  @Test
  @DisplayName("nonce不匹配_invalid_claims")
  void wrongNonce_rejected() throws Exception {
    String token = sign(goodKey, claims(c -> {}));
    assertFailsWith(token, "different-nonce", OidcErrorCode.INVALID_CLAIMS);
  }

  @Test
  @DisplayName("过期超出偏移窗_expired_token；窗内仍放行")
  void expiryWindow() throws Exception {
    String expired =
        sign(goodKey, claims(c -> c.expirationTime(Date.from(Instant.now().minusSeconds(120)))));
    assertFailsWith(expired, NONCE, OidcErrorCode.EXPIRED_TOKEN);

    String withinSkew =
        sign(goodKey, claims(c -> c.expirationTime(Date.from(Instant.now().minusSeconds(30)))));
    assertThat(validator.validate(withinSkew, NONCE)).isNotNull();
  }

  @Test
  @DisplayName("iat在未来超出偏移窗_invalid_claims")
  void futureIssuedAt_rejected() throws Exception {
    String token =
        sign(goodKey, claims(c -> c.issueTime(Date.from(Instant.now().plusSeconds(300)))));
    assertFailsWith(token, NONCE, OidcErrorCode.INVALID_CLAIMS);
  }

  @Test
  @DisplayName("缺sub_invalid_claims")
  void missingSubject_rejected() throws Exception {
    String token = sign(goodKey, claims(c -> c.subject(null)));
    assertFailsWith(token, NONCE, OidcErrorCode.INVALID_CLAIMS);
  }

  private void assertFailsWith(String token, String nonce, OidcErrorCode expected) {
    assertThatThrownBy(() -> validator.validate(token, nonce))
        .isInstanceOf(OidcFlowException.class)
        .extracting(ex -> ((OidcFlowException) ex).code())
        .isEqualTo(expected);
  }

  private static JWTClaimsSet claims(Consumer<JWTClaimsSet.Builder> customizer) {
    JWTClaimsSet.Builder builder =
        new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .subject("sub-1")
            .audience(CLIENT_ID)
            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .issueTime(Date.from(Instant.now()))
            .claim("nonce", NONCE);
    customizer.accept(builder);
    return builder.build();
  }

  private static String sign(RSAKey key, JWTClaimsSet claims) throws Exception {
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }
}
