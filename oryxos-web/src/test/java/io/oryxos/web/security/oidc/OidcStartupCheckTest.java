package io.oryxos.web.security.oidc;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.oryxos.web.config.WebAuthProperties;
import io.oryxos.web.config.WebOidcProperties;
import io.oryxos.web.config.WebRbacProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 040 验收 harness：OidcStartupCheck——组合矩阵钉死（FR-002/FR-012，R12）。 守：默认关跳过、必填缺失点名、auth 关即拒、rbac 关仅 WARN
 * 不阻断、不碰网络（无任何 IdP 交互即可判定）。
 */
class OidcStartupCheckTest {

  private WebOidcProperties oidc;
  private WebAuthProperties auth;
  private WebRbacProperties rbac;

  @BeforeEach
  void setUp() {
    oidc = new WebOidcProperties();
    auth = new WebAuthProperties();
    rbac = new WebRbacProperties();
  }

  private OidcStartupCheck check() {
    return new OidcStartupCheck(oidc, auth, rbac);
  }

  private void fillRequired() {
    oidc.setIssuer("https://idp.example.com/realm");
    oidc.setClientId("oryxos-admin");
    oidc.setClientSecret("secret");
    oidc.setRedirectBaseUrl("https://oryxos.example.com");
  }

  @Test
  @DisplayName("默认关_跳过全部校验")
  void disabled_skipped() {
    assertThatCode(() -> check().run(null)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("开启但必填缺失_启动即拒并点名缺失项")
  void enabled_missingRequired_failsWithNames() {
    oidc.setEnabled(true);
    oidc.setIssuer("https://idp.example.com/realm");
    auth.setEnabled(true);

    assertThatThrownBy(() -> check().run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("oryxos.web.oidc.client-id")
        .hasMessageContaining("oryxos.web.oidc.client-secret")
        .hasMessageContaining("oryxos.web.oidc.redirect-base-url")
        .hasMessageNotContaining("oryxos.web.oidc.issuer,");
  }

  @Test
  @DisplayName("开启但auth关_启动即拒（OIDC是管理台登录方式）")
  void enabled_authOff_fails() {
    oidc.setEnabled(true);
    fillRequired();
    auth.setEnabled(false);

    assertThatThrownBy(() -> check().run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("oryxos.web.auth.enabled");
  }

  @Test
  @DisplayName("开启且配置齐全auth开_通过；rbac关仅WARN不阻断")
  void enabled_complete_passes_rbacOffOnlyWarns() {
    oidc.setEnabled(true);
    fillRequired();
    auth.setEnabled(true);
    rbac.setEnabled(false);

    assertThatCode(() -> check().run(null)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("非https非回环redirect-base-url_仅WARN不阻断")
  void plainHttpNonLoopback_onlyWarns() {
    oidc.setEnabled(true);
    fillRequired();
    oidc.setRedirectBaseUrl("http://oryxos.internal:8080");
    auth.setEnabled(true);

    assertThatCode(() -> check().run(null)).doesNotThrowAnyException();
  }
}
