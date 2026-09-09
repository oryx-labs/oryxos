package io.oryxos.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.execution.ExecutionBackendSnapshot;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 024 T017：状态 API 的数据面（探针注入桩）——daemon 三态呈现、镜像 digest 仅 docker 档、覆写一览过滤。 */
class ExecutionBackendControllerTest {

  /** 桩探针：按命令前缀回放（null=通过；非 null=诊断错误）。 */
  private static final class StubProbe implements ExecutionBackendController.DockerStatusProbe {
    String cliError;
    String daemonError;

    @Override
    public String probe(List<String> argv) {
      String key = String.join(" ", argv);
      if (key.startsWith("docker --version") && cliError != null) {
        return cliError;
      }
      if (key.startsWith("docker info") && daemonError != null) {
        return daemonError;
      }
      return null;
    }
  }

  private static ProfileRegistry registry(Profile... profiles) {
    ProfileRegistry registry = new ProfileRegistry();
    for (Profile profile : profiles) {
      registry.register(profile);
    }
    return registry;
  }

  private static Profile agent(String name, Profile.Sandbox sandbox) {
    return new Profile(
        name,
        null,
        null,
        null,
        new Profile.ProviderRef("deepseek", "deepseek-chat", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        null,
        sandbox);
  }

  @Test
  @DisplayName("docker档_daemon就绪_覆写一览只列声明段")
  void dockerBackendHealthyWithOverrides() {
    StubProbe probe = new StubProbe();
    ExecutionBackendController controller =
        new ExecutionBackendController(
            new ExecutionBackendSnapshot("docker", "alpine:3.20", null, null, null, null),
            registry(
                agent("ops", new Profile.Sandbox("local", null, null)),
                agent("plain", null),
                agent("batch", new Profile.Sandbox("docker", "1g", "2.0"))),
            probe);

    ExecutionBackendController.StatusView status = controller.status().getData();

    assertEquals("docker", status.backend());
    assertTrue(status.docker().reachable());
    assertEquals(
        List.of(
            new ExecutionBackendController.AgentOverrideView("ops", "local", null, null),
            new ExecutionBackendController.AgentOverrideView("batch", "docker", "1g", "2.0")),
        status.agentOverrides(),
        "未声明 sandbox 段的 plain 不列");
  }

  @Test
  @DisplayName("daemon不可达_标红呈现错误信息_不抛异常")
  void daemonDownRenderedAsError() {
    StubProbe probe = new StubProbe();
    probe.daemonError = "Cannot connect to the Docker daemon";
    ExecutionBackendController controller =
        new ExecutionBackendController(
            new ExecutionBackendSnapshot("docker", "alpine:3.20", null, null, null, null),
            registry(),
            probe);

    ExecutionBackendController.StatusView status = controller.status().getData();

    assertFalse(status.docker().reachable());
    assertTrue(status.docker().error().contains("daemon"));
    assertNull(status.docker().version());
  }

  @Test
  @DisplayName("CLI缺失_三态第一级")
  void cliMissingRendered() {
    StubProbe probe = new StubProbe();
    probe.cliError = "CreateProcess error=2";
    ExecutionBackendController controller =
        new ExecutionBackendController(
            new ExecutionBackendSnapshot("docker", "alpine:3.20", null, null, null, null),
            registry(),
            probe);

    assertFalse(controller.status().getData().docker().reachable());
  }

  @Test
  @DisplayName("local档_不探测镜像_digest为空（daemon探测仍执行——运维想看环境状态）")
  void localBackendSkipsImageOnly() {
    StubProbe probe = new StubProbe();
    ExecutionBackendController controller =
        new ExecutionBackendController(
            new ExecutionBackendSnapshot("local", null, null, null, null, null), registry(), probe);

    ExecutionBackendController.StatusView status = controller.status().getData();

    assertEquals("local", status.backend());
    assertTrue(status.docker().reachable(), "daemon 状态照常呈现（环境信息）");
    assertNull(status.imageDigest());
  }
}
