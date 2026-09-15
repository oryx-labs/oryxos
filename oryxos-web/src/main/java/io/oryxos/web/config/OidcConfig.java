package io.oryxos.web.config;

import io.oryxos.web.security.oidc.IdTokenValidator;
import io.oryxos.web.security.oidc.JwksCache;
import io.oryxos.web.security.oidc.OidcClient;
import io.oryxos.web.security.oidc.OidcRoleResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * OIDC 组件装配（040）：客户端 / JWKS 缓存 / ID Token 验证器 / 角色映射器。
 *
 * <p>无条件装配（构造零网络零副作用）：{@code oidc.enabled=false} 时端点返回 404，组件闲置零开销—— 与 {@code AuthorizationConfig}
 * 的「开关在行为层不在装配层」惯例一致。
 */
@Configuration(proxyBeanMethods = false)
public class OidcConfig {

  @Bean
  OidcClient oidcClient(WebOidcProperties properties, RestClient.Builder restClientBuilder) {
    return new OidcClient(properties, restClientBuilder);
  }

  @Bean
  JwksCache jwksCache(OidcClient oidcClient) {
    return new JwksCache(oidcClient);
  }

  @Bean
  IdTokenValidator idTokenValidator(WebOidcProperties properties, JwksCache jwksCache) {
    return new IdTokenValidator(properties, jwksCache);
  }

  @Bean
  OidcRoleResolver oidcRoleResolver(WebOidcProperties properties) {
    return new OidcRoleResolver(properties);
  }
}
