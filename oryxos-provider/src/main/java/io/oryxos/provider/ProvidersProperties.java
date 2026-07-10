package io.oryxos.provider;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 全局层 provider 清单（application.yaml 的 {@code oryxos.providers}）。
 *
 * <p>凭证一律 {@code ${ENV_VAR}} 占位由 Spring 解析；启动校验缺失即点名报错，不静默（宪法 VI）。
 */
@ConfigurationProperties(prefix = "oryxos")
public record ProvidersProperties(List<ProviderConfig> providers) {

  public ProvidersProperties {
    providers = providers == null ? List.of() : List.copyOf(providers);
  }

  /** 单个 provider 的连接配置。 */
  public record ProviderConfig(String name, String apiKey, String baseUrl) {}

  /** 启动校验：名非空不重复、api-key 已解析非空、base-url 非空。失败信息必须点名。 */
  public void validate() {
    Set<String> seen = new HashSet<>();
    for (ProviderConfig config : providers) {
      if (config.name() == null || config.name().isBlank()) {
        throw new IllegalStateException("oryxos.providers 存在未命名的 provider 条目");
      }
      if (!seen.add(config.name())) {
        throw new IllegalStateException("oryxos.providers 中 provider 名重复: " + config.name());
      }
      if (config.apiKey() == null || config.apiKey().isBlank() || config.apiKey().contains("${")) {
        throw new IllegalStateException(
            "provider " + config.name() + " 的 api-key 未配置或环境变量未解析，请检查对应环境变量");
      }
      if (config.baseUrl() == null || config.baseUrl().isBlank()) {
        throw new IllegalStateException("provider " + config.name() + " 缺少 base-url");
      }
    }
  }

  public Set<String> names() {
    Set<String> names = new HashSet<>();
    for (ProviderConfig config : providers) {
      names.add(config.name());
    }
    return names;
  }
}
