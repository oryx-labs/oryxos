package io.oryxos.web.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 管理台认证配置装配（012-web-auth）。
 *
 * <p>web 模块自注册 {@link WebAuthProperties}——因 {@code OryxOsRuntime}（cli）不依赖 web 模块， 无法像 provider/tool
 * 的 properties 那样在 cli 的 {@code @EnableConfigurationProperties} 列表里显式注册。 web 自管自注册，cli 靠 {@code
 * scanBasePackages="io.oryxos"} 运行时发现本类即可。
 */
@Configuration
@EnableConfigurationProperties(WebAuthProperties.class)
public class WebAuthConfig {}
