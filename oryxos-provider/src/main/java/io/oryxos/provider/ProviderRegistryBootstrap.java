package io.oryxos.provider;

import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ProviderRegistryBootstrap {

  private static final Logger LOG = LoggerFactory.getLogger(ProviderRegistryBootstrap.class);

  private final ProviderRegistryValidator validator;

  public ProviderRegistryBootstrap(ProviderRegistryValidator validator) {
    this.validator = validator;
  }

  public void seedMissing(ProviderRegistry registry, ProvidersProperties properties) {
    for (ProvidersProperties.ProviderConfig config : properties.providers()) {
      String name = config.name();
      if (name != null && !name.isBlank() && registry.exists(name)) {
        continue;
      }
      ProviderDef candidate =
          new ProviderDef(config.name(), config.apiKey(), config.baseUrl(), null);
      Optional<String> violation = validator.violation(candidate);
      if (violation.isPresent()) {
        LOG.warn(
            "跳过 provider {} 的启动播种: {}",
            sanitizeLogValue(config.name()),
            sanitizeLogValue(violation.get()));
        continue;
      }
      registry.save(candidate);
    }
  }

  private static String sanitizeLogValue(String value) {
    return value == null ? "<unknown>" : value.replace('\r', '_').replace('\n', '_');
  }
}
