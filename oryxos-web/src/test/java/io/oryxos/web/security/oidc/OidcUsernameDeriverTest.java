package io.oryxos.web.security.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 040 验收 harness：用户名派生优先级与清洗规则钉死（preferred_username → email → sub 兜底）。 */
class OidcUsernameDeriverTest {

  @Test
  @DisplayName("preferred_username优先且小写化清洗")
  void preferredUsernameFirst() {
    assertThat(OidcUsernameDeriver.derive("Alice.Wang", "other@corp.com", "sub-1"))
        .isEqualTo("alice.wang");
  }

  @Test
  @DisplayName("无preferred_username_取email本地部")
  void emailLocalPartSecond() {
    assertThat(OidcUsernameDeriver.derive(null, "Bob_Li@corp.com", "sub-1")).isEqualTo("bob_li");
    assertThat(OidcUsernameDeriver.derive("  ", "carol@corp.com", "sub-1")).isEqualTo("carol");
  }

  @Test
  @DisplayName("均缺失_oidc-前缀+sub兜底")
  void subFallback() {
    assertThat(OidcUsernameDeriver.derive(null, null, "ABC123")).isEqualTo("oidc-abc123");
  }

  @Test
  @DisplayName("非法字符剔除_全非法时降级到下一来源")
  void sanitization() {
    assertThat(OidcUsernameDeriver.derive("张三", "zhang.san@corp.com", "sub-1"))
        .isEqualTo("zhang.san");
    assertThat(OidcUsernameDeriver.derive("a b\tc", null, "s")).isEqualTo("abc");
  }

  @Test
  @DisplayName("超长截断到60字符（给冲突后缀留位）")
  void lengthCapped() {
    assertThat(OidcUsernameDeriver.derive("x".repeat(100), null, "s")).hasSize(60);
    assertThat(OidcUsernameDeriver.derive(null, null, "y".repeat(100)))
        .hasSizeLessThanOrEqualTo(60);
  }

  @Test
  @DisplayName("sub也无法产出_user兜底")
  void ultimateFallback() {
    assertThat(OidcUsernameDeriver.derive(null, null, "!!!")).isEqualTo("oidc-user");
  }
}
