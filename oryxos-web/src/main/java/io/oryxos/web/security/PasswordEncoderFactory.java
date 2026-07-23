package io.oryxos.web.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码哈希装配（012-web-auth）。
 *
 * <p>提供 {@link DelegatingPasswordEncoder}（{@code {bcrypt}} 前缀，将来升 Argon2 无迁移——FR-009）。 单 jar {@code
 * spring-security-crypto}，非 {@code spring-boot-starter-security} 全套（无 filter chain / autoconfig /
 * RBAC），不违"自实现核心"宪法精神。component scan（{@code OryxOsRuntime} 扫 {@code io.oryxos}）自动注册此 @Bean。
 */
@Configuration
public class PasswordEncoderFactory {

  @Bean
  PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }
}
