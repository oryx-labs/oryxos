package io.oryxos.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.storage.WebSessionService;
import io.oryxos.storage.WebUserService;
import io.oryxos.web.security.BasicAuthFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * BasicAuthFilter 注册（012-web-auth）。
 *
 * <p>{@code addUrlPatterns("/admin/*")} 精确限定只拦管理台 SPA；{@code /api/v1/**} 不在模式内、 天然不受影响（SC-003），
 * {@code /api/v1/health} 亦免认证（FR-008）。filter 在 DispatcherServlet 之前跑，不依赖
 * {@code @RestControllerAdvice}。 {@code /admin/login} 放行在 filter 内部判路径（{@link
 * BasicAuthFilter#doFilterInternal}）。
 */
@Configuration
public class AuthFilterConfig {

  @Bean
  FilterRegistrationBean<BasicAuthFilter> basicAuthFilter(
      WebUserService userService,
      WebSessionService sessionService,
      WebAuthProperties properties,
      ObjectMapper objectMapper) {
    FilterRegistrationBean<BasicAuthFilter> registration = new FilterRegistrationBean<>();
    registration.setFilter(
        new BasicAuthFilter(userService, sessionService, properties, objectMapper));
    registration.addUrlPatterns("/admin/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return registration;
  }
}
