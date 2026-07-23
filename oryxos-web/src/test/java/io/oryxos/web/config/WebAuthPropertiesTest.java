package io.oryxos.web.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 012-web-auth 验收 harness：WebAuthPropertiesTest——配置绑定（默认关、yml 覆盖生效）钉死。 用
 * ApplicationContextRunner（轻量，不起完整 server）。
 */
class WebAuthPropertiesTest {

  @Test
  @DisplayName("默认_enabled=false_realm=OryxOS")
  void defaults_enabledFalse_realmOryxOS() {
    new ApplicationContextRunner()
        .withUserConfiguration(WebAuthConfig.class)
        .run(
            context -> {
              WebAuthProperties props = context.getBean(WebAuthProperties.class);
              assertFalse(props.isEnabled());
              assertEquals("OryxOS", props.getRealm());
            });
  }

  @Test
  @DisplayName("yml覆盖_enabled=true_realm自定义生效")
  void override_ymlTakesEffect() {
    new ApplicationContextRunner()
        .withUserConfiguration(WebAuthConfig.class)
        .withPropertyValues("oryxos.web.auth.enabled=true", "oryxos.web.auth.realm=Custom")
        .run(
            context -> {
              WebAuthProperties props = context.getBean(WebAuthProperties.class);
              assertEquals(true, props.isEnabled());
              assertEquals("Custom", props.getRealm());
            });
  }
}
