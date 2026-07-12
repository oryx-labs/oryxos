package io.oryxos.tool.sandbox;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** HTTP 域名白名单配置（键 {@code http.allowed_domains}）。支持 {@code *.} 通配（带点号边界）；空列表 = deny-all。 */
@ConfigurationProperties(prefix = "http")
public record HttpSandboxProperties(List<String> allowedDomains) {

  public HttpSandboxProperties {
    // null = deny-all；copyOf 固化不可变（SpotBugs EI_EXPOSE）
    allowedDomains = allowedDomains == null ? List.of() : List.copyOf(allowedDomains);
  }
}
