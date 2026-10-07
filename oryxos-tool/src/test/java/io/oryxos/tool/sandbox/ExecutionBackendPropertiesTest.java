package io.oryxos.tool.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * 024 T002：执行后端配置绑定与默认值钉死（SC-001 锚点——默认 local 档的一切默认值在此锁死， 后续任何「顺手改默认」都会被本测试拦截）。模式镜像
 * SandboxDefaultsConfigTest。
 */
class ExecutionBackendPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);

  @Test
  @DisplayName("默认_local档_512m_1.0CPU_断网_非root_image空")
  void defaults_localAndSafeLimits() {
    runner.run(
        context -> {
          ExecutionBackendProperties props = context.getBean(ExecutionBackendProperties.class);
          assertEquals("local", props.backend());
          assertEquals("", props.image());
          assertEquals("512m", props.memory());
          assertEquals("1.0", props.cpus());
          assertEquals("none", props.network());
          assertEquals("65534:65534", props.user());
          assertFalse(props.isDocker());
        });
  }

  @Test
  @DisplayName("yml覆盖_docker档_自定义镜像与限额生效")
  void override_dockerWithCustomLimits() {
    runner
        .withPropertyValues(
            "oryxos.sandbox.execution.backend=docker",
            "oryxos.sandbox.execution.image=python:3.12-alpine",
            "oryxos.sandbox.execution.memory=1g",
            "oryxos.sandbox.execution.cpus=2.0")
        .run(
            context -> {
              ExecutionBackendProperties props = context.getBean(ExecutionBackendProperties.class);
              assertEquals("docker", props.backend());
              assertEquals("python:3.12-alpine", props.image());
              assertEquals("1g", props.memory());
              assertEquals("2.0", props.cpus());
              // 未覆盖的项回落默认（安全参数不因部分配置而丢失）
              assertEquals("none", props.network());
              assertEquals("65534:65534", props.user());
              assertTrue(props.isDocker());
            });
  }

  @Test
  @DisplayName("空白值归一化_blank网络回落none而非null")
  void blankValuesNormalizedToDefaults() {
    runner
        .withPropertyValues(
            "oryxos.sandbox.execution.backend= ", "oryxos.sandbox.execution.network=")
        .run(
            context -> {
              ExecutionBackendProperties props = context.getBean(ExecutionBackendProperties.class);
              assertEquals("local", props.backend());
              assertEquals("none", props.network());
            });
  }

  @Test
  @DisplayName("backend 写错_绑定即失败_不静默落 local 宿主执行")
  void unknownBackendFailsBinding() {
    runner
        .withPropertyValues("oryxos.sandbox.execution.backend=Docker")
        .run(
            context -> {
              // 以前这个上下文会正常起来：'Docker' 既不 isDocker 也不 isSsh，select() 直接落 local。
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("Docker")
                  .hasStackTraceContaining("local/docker/ssh");
            });
  }

  @Configuration
  @EnableConfigurationProperties(ExecutionBackendProperties.class)
  static class PropertiesConfiguration {}
}
