package io.oryxos.web.security;

import io.oryxos.core.provider.ProviderRegistry;
import io.oryxos.provider.ProviderRegistryValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * Provider 配置启动校验（012-web-auth fix）。
 *
 * <p>仅在 SERVLET web 模式（serve/gateway）执行——WebApplicationType.NONE 自身不触发；chat 在进入会话前显式校验， user
 * 等轻命令仍不会因 Provider 未配置而被阻断。
 *
 * <p>实现 {@link SmartInitializingSingleton} 而非 {@code ApplicationRunner}：校验在所有单例 Bean 初始化完成后、Web
 * Server 开始监听端口之前执行。配置不合法时 ApplicationContext 启动直接失败， 端口不会打开，避免健康检查在 Provider 校验之前返回 200
 * 的竞态（false-positive startup）。
 *
 * <p>校验逻辑委托给 {@link ProviderRegistryValidator}，确保检查的是 YAML 播种后的最终注册表。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ProviderStartupCheck implements SmartInitializingSingleton {

  private static final Logger LOG = LoggerFactory.getLogger(ProviderStartupCheck.class);

  private final ProviderRegistry registry;
  private final ProviderRegistryValidator validator;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "ProviderRegistry is a shared Spring bean dependency and is intentionally retained; "
              + "copying or wrapping it would break bean semantics.")
  public ProviderStartupCheck(ProviderRegistry registry, ProviderRegistryValidator validator) {
    this.registry = registry;
    this.validator = validator;
  }

  @Override
  public void afterSingletonsInstantiated() {
    validator.validate(registry);
    LOG.debug("Provider startup check passed ({} provider(s) configured)", registry.list().size());
  }
}
