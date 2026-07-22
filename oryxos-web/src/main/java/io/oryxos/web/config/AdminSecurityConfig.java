package io.oryxos.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.web.auth.LocalAdminIdentityService;
import io.oryxos.web.auth.LoginFailureTracker;
import jakarta.servlet.ServletContext;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

/** Spring Security setup for the OryxOS management console and API. */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AdminSecurityProperties.class)
public class AdminSecurityConfig {

  @Bean
  @ConditionalOnMissingBean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean
  LoginFailureTracker loginFailureTracker(Clock clock) {
    return new LoginFailureTracker(clock);
  }

  @Bean
  @ConditionalOnMissingBean
  PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  @Bean
  @ConditionalOnMissingBean
  LocalAdminIdentityService localAdminIdentityService(
      AdminSecurityProperties properties, PasswordEncoder passwordEncoder) {
    return new LocalAdminIdentityService(properties, passwordEncoder);
  }

  @Bean
  @ConditionalOnMissingBean
  SecurityContextRepository securityContextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  ServletContextInitializer adminSessionCookieInitializer(AdminSecurityProperties properties) {
    return servletContext -> applySessionCookiePolicy(servletContext, properties);
  }

  @Bean
  SecurityFilterChain adminSecurityFilterChain(
      HttpSecurity http,
      ObjectMapper objectMapper,
      SecurityContextRepository securityContextRepository)
      throws Exception {
    SecurityApiResponseWriter writer = new SecurityApiResponseWriter(objectMapper);
    CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
    return http.csrf(csrf -> csrf.csrfTokenRepository(csrfRepository))
        .securityContext(context -> context.securityContextRepository(securityContextRepository))
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(
                        (request, response, ex) ->
                            writer.write(response, 401, "Authentication required"))
                    .accessDeniedHandler(
                        (request, response, ex) -> writer.write(response, 403, "Access denied")))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.GET, "/api/v1/health")
                    .permitAll()
                    .requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/auth/status")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/login")
                    .permitAll()
                    .requestMatchers("/admin", "/admin/**")
                    .permitAll()
                    .requestMatchers("/api/v1/**")
                    .authenticated()
                    .requestMatchers("/swagger-ui/**", "/v3/api-docs/**")
                    .authenticated()
                    .requestMatchers("/actuator/**")
                    .authenticated()
                    .anyRequest()
                    .permitAll())
        .build();
  }

  private static void applySessionCookiePolicy(
      ServletContext servletContext, AdminSecurityProperties properties) {
    servletContext.getSessionCookieConfig().setHttpOnly(true);
    servletContext.getSessionCookieConfig().setSecure(properties.isSecureCookie());
    int timeoutSeconds = Math.toIntExact(properties.getSessionIdleTimeout().toSeconds());
    servletContext.setSessionTimeout(Math.max(1, timeoutSeconds / 60));
  }
}
