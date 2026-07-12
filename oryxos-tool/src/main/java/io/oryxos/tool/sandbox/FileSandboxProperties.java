package io.oryxos.tool.sandbox;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 文件路径白名单配置（键 {@code file.allowed_paths}）。空列表语义为"什么都不允许"（deny-all）—— 配置缺失绝不退化为放行（宪法 VI）。 */
@ConfigurationProperties(prefix = "file")
public record FileSandboxProperties(List<String> allowedPaths) {

  public FileSandboxProperties {
    // null（配置键缺省）= deny-all；copyOf 固化不可变，杜绝外部篡改白名单（SpotBugs EI_EXPOSE）
    allowedPaths = allowedPaths == null ? List.of() : List.copyOf(allowedPaths);
  }
}
