package io.oryxos.provider;

import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class ProviderRegistryValidator {

  private static final String MOCK = "mock";

  public void validate(ProviderRegistry registry) {
    List<ProviderDef> providers = registry.list();
    if (providers.isEmpty()) {
      throw new IllegalStateException("没有可用的 Provider，请先配置 Provider");
    }
    Set<String> names = new HashSet<>();
    for (ProviderDef provider : providers) {
      Optional<String> violation = violation(provider);
      if (violation.isPresent()) {
        throw new IllegalStateException(
            "provider "
                + safeName(provider == null ? null : provider.name())
                + " "
                + violation.get());
      }
      if (!names.add(provider.name())) {
        throw new IllegalStateException("Provider 注册表名称重复: " + safeName(provider.name()));
      }
    }
  }

  Optional<String> violation(ProviderDef provider) {
    if (provider == null || provider.name() == null || provider.name().isBlank()) {
      return Optional.of("名称为空");
    }
    if (MOCK.equals(provider.name())) {
      return Optional.empty();
    }
    if (provider.apiKey() == null
        || provider.apiKey().isBlank()
        || provider.apiKey().contains("${")) {
      return Optional.of("的 api-key 未配置");
    }
    if (provider.baseUrl() == null || provider.baseUrl().isBlank()) {
      return Optional.of("的 base-url 未配置");
    }
    return Optional.empty();
  }

  private static String safeName(String name) {
    return name == null ? "<unknown>" : name.replace('\r', '_').replace('\n', '_');
  }
}
