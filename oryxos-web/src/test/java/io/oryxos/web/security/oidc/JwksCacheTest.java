package io.oryxos.web.security.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.Instant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 040 验收 harness：JwksCache——缓存/未知 kid 限速强刷/失败沿用旧缓存钉死（R11）。 */
class JwksCacheTest {

  private static String jwksJson;

  private OidcClient client;
  private JwksCache cache;

  @BeforeAll
  static void generateJwks() throws Exception {
    RSAKey key = new RSAKeyGenerator(2048).keyID("kid-1").generate();
    jwksJson = new JWKSet(key.toPublicJWK()).toString();
  }

  @BeforeEach
  void setUp() {
    client = mock(OidcClient.class);
    when(client.fetchJwksJson()).thenReturn(jwksJson);
    cache = new JwksCache(client);
  }

  @Test
  @DisplayName("首次按kid取键_触发拉取并缓存_后续命中零拉取")
  void firstFetch_thenCached() {
    assertThat(cache.keyFor("kid-1")).isNotNull();
    assertThat(cache.keyFor("kid-1")).isNotNull();
    verify(client, times(1)).fetchJwksJson();
  }

  @Test
  @DisplayName("未知kid_限速强刷一次_60秒内不重复拉取")
  void unknownKid_rateLimitedRefresh() {
    assertThat(cache.keyFor("kid-1")).isNotNull();
    // 未知 kid：缓存刚拉过（限速窗内），不再打 IdP
    assertThat(cache.keyFor("rogue-kid")).isNull();
    verify(client, times(1)).fetchJwksJson();

    // 把上次拉取时间拨回 61 秒前：未知 kid 触发强刷
    ReflectionTestUtils.setField(cache, "lastRefreshAttempt", Instant.now().minusSeconds(61));
    assertThat(cache.keyFor("rogue-kid")).isNull();
    verify(client, times(2)).fetchJwksJson();
  }

  @Test
  @DisplayName("刷新失败_沿用旧缓存不抛")
  void refreshFailure_staleServed() {
    assertThat(cache.keyFor("kid-1")).isNotNull();
    when(client.fetchJwksJson())
        .thenThrow(new OidcFlowException(OidcErrorCode.IDP_UNREACHABLE, "down"));
    // 缓存过期 + 限速窗外：强刷失败 → 沿用旧缓存
    ReflectionTestUtils.setField(cache, "fetchedAt", Instant.now().minusSeconds(16 * 60));
    ReflectionTestUtils.setField(cache, "lastRefreshAttempt", Instant.now().minusSeconds(120));
    assertThat(cache.keyFor("kid-1")).isNotNull();
  }

  @Test
  @DisplayName("无缓存且拉取失败_上抛idp_unreachable（不放行）")
  void noCacheAndFailure_throws() {
    when(client.fetchJwksJson())
        .thenThrow(new OidcFlowException(OidcErrorCode.IDP_UNREACHABLE, "down"));
    JwksCache cold = new JwksCache(client);
    assertThatThrownBy(() -> cold.keyFor("kid-1"))
        .isInstanceOf(OidcFlowException.class)
        .extracting(ex -> ((OidcFlowException) ex).code())
        .isEqualTo(OidcErrorCode.IDP_UNREACHABLE);
  }

  @Test
  @DisplayName("无kid的单钥IdP_唯一键即命中")
  void noKid_singleKeyFallback() {
    assertThat(cache.keyFor(null)).isNotNull();
    assertThat(cache.keyFor("")).isNotNull();
  }
}
