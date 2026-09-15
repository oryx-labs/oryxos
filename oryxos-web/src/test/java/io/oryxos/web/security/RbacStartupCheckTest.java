package io.oryxos.web.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.storage.WebUserService;
import io.oryxos.web.config.WebApiKeyProperties;
import io.oryxos.web.config.WebAuthProperties;
import io.oryxos.web.config.WebRbacProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 039 FR-012：RbacStartupCheck——rbac 开时 apikey/ADMIN fail-closed；auth 关仅 WARN。 */
class RbacStartupCheckTest {

  private WebRbacProperties rbacProperties;
  private WebApiKeyProperties apiKeyProperties;
  private WebAuthProperties authProperties;
  private WebUserService userService;
  private RbacStartupCheck check;

  @BeforeEach
  void setUp() {
    rbacProperties = new WebRbacProperties();
    apiKeyProperties = new WebApiKeyProperties();
    authProperties = new WebAuthProperties();
    userService = mock(WebUserService.class);
    check = new RbacStartupCheck(rbacProperties, apiKeyProperties, authProperties, userService);
  }

  @Test
  @DisplayName("rbac关闭_run不抛异常")
  void rbacOff_doesNotThrow() {
    rbacProperties.setEnabled(false);
    apiKeyProperties.setEnabled(false);
    authProperties.setEnabled(false);

    assertThatCode(() -> check.run(null)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("rbac开且apikey关_抛IllegalStateException")
  void rbacOnApiKeyOff_throws() {
    rbacProperties.setEnabled(true);
    apiKeyProperties.setEnabled(false);
    authProperties.setEnabled(true);
    when(userService.hasAdminAccount()).thenReturn(true);

    assertThatThrownBy(() -> check.run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("oryxos.web.apikey.enabled");
  }

  @Test
  @DisplayName("rbac开且apikey开但无ADMIN_抛IllegalStateException")
  void rbacOnNoAdmin_throws() {
    rbacProperties.setEnabled(true);
    apiKeyProperties.setEnabled(true);
    authProperties.setEnabled(true);
    when(userService.hasAdminAccount()).thenReturn(false);

    assertThatThrownBy(() -> check.run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("oryxos user role");
  }

  @Test
  @DisplayName("rbac开且apikey开且有ADMIN_不抛异常")
  void rbacOnHealthy_ok() {
    rbacProperties.setEnabled(true);
    apiKeyProperties.setEnabled(true);
    authProperties.setEnabled(true);
    when(userService.hasAdminAccount()).thenReturn(true);

    assertThatCode(() -> check.run(null)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("rbac开且auth关_不抛异常_仅WARN路径")
  void rbacOnAuthOff_doesNotThrow() {
    rbacProperties.setEnabled(true);
    apiKeyProperties.setEnabled(true);
    authProperties.setEnabled(false);
    when(userService.hasAdminAccount()).thenReturn(true);

    assertThatCode(() -> check.run(null)).doesNotThrowAnyException();
  }
}
