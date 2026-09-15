package io.oryxos.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.storage.AuthEventRecorder;
import io.oryxos.storage.IdentityMappingService;
import io.oryxos.storage.WebSessionService;
import io.oryxos.storage.WebUserService;
import io.oryxos.web.oidc.HttpOidcTokenClient;
import io.oryxos.web.oidc.OidcAuthService;
import io.oryxos.web.oidc.OidcPendingStore;
import io.oryxos.web.oidc.OidcTokenClient;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OIDC 装配（040）。properties 始终注册；业务 bean 在 web 扫描下可用。TokenClient 可被测试替换。 */
@Configuration
public class OidcConfig {

  @Bean
  OidcPendingStore oidcPendingStore() {
    return new OidcPendingStore(Duration.ofMinutes(10));
  }

  @Bean
  @ConditionalOnMissingBean(OidcTokenClient.class)
  OidcTokenClient oidcTokenClient(ObjectMapper objectMapper) {
    return new HttpOidcTokenClient(objectMapper);
  }

  @Bean
  OidcAuthService oidcAuthService(
      WebOidcProperties properties,
      OidcTokenClient tokenClient,
      OidcPendingStore pendingStore,
      IdentityMappingService mappingService,
      WebUserService userService,
      WebSessionService sessionService,
      AuthEventRecorder authEventRecorder) {
    return new OidcAuthService(
        properties,
        tokenClient,
        pendingStore,
        mappingService,
        userService,
        sessionService,
        authEventRecorder);
  }
}
