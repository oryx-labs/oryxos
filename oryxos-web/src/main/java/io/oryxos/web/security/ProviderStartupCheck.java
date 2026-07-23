package io.oryxos.web.security;

import io.oryxos.provider.ProvidersProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * Provider 配置启动校验（012-web-auth fix）。
 *
 * <p>仅在 SERVLET web 模式（serve/gateway）执行——user/chat 等 WebApplicationType.NONE 命令不触发，
 * 确保账号管理等不依赖 LLM 的命令不会因 api-key 未配而被阻断。
 *
 * <p>校验逻辑委托给 {@link ProvidersProperties#validate()}，镜像 {@link AuthStartupCheck} 的模式。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ProviderStartupCheck implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(ProviderStartupCheck.class);

  private final ProvidersProperties properties;

  public ProviderStartupCheck(ProvidersProperties properties) {
    this.properties = properties;
  }

  @Override
  public void run(ApplicationArguments args) {
    properties.validate();
    LOG.debug("Provider startup check passed ({} provider(s) configured)", properties.providers().size());
  }
}
