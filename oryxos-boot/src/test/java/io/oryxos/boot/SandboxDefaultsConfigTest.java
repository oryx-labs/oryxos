package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.oryxos.tool.sandbox.ShellSandboxProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** 锁定内置 application.yml 的最小 shell 权限，防止解释器再次进入默认白名单。 */
class SandboxDefaultsConfigTest {

  @Test
  void defaultShellWhitelistExcludesInterpreters() {
    new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(ShellPropertiesConfiguration.class)
        .run(
            context -> {
              ShellSandboxProperties properties = context.getBean(ShellSandboxProperties.class);
              assertEquals(List.of("ls", "cat", "echo", "grep"), properties.allowedCommands());
              assertFalse(properties.allowedCommands().contains("python"));
              assertFalse(properties.allowedCommands().contains("python3"));
            });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(ShellSandboxProperties.class)
  static class ShellPropertiesConfiguration {}
}
