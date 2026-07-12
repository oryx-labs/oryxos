package io.oryxos.tool.sandbox;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Shell 命令白名单配置（键 {@code shell.allowed_commands}）。比对命令首 token；空列表 = deny-all。 */
@ConfigurationProperties(prefix = "shell")
public record ShellSandboxProperties(List<String> allowedCommands) {

  public ShellSandboxProperties {
    // null = deny-all；copyOf 固化不可变（SpotBugs EI_EXPOSE）
    allowedCommands = allowedCommands == null ? List.of() : List.copyOf(allowedCommands);
  }
}
