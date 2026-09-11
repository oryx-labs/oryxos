package io.oryxos.tool.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.oryxos.core.agent.ToolExecutionContext;
import io.oryxos.core.profile.Profile;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 024 T015（SC-008 选档路由 + D8 收敛优先级）：Agent 覆写 > 全局；非法/缺省继承；docker 档收到生效配置。 */
class AgentAwareProcessStarterTest {

  @AfterEach
  void cleanup() {
    ToolExecutionContext.clear();
  }

  // 每测试新实例：计数器不跨测试污染（静态共享曾导致路由断言互串）
  private final RecordingLocal local = new RecordingLocal();
  private final RecordingDocker docker = new RecordingDocker();

  private AgentAwareProcessStarter starter(
      java.util.function.Function<String, Profile.Sandbox> lookup) {
    return new AgentAwareProcessStarter(
        new ExecutionBackendProperties("docker", "alpine:3.20", "512m", "1.0", null, null),
        lookup,
        local,
        props -> docker.withProps(props));
  }

  @Test
  @DisplayName("收敛_覆写优先_未覆写继承全局_镜像网络不可覆写")
  void resolveOverrideThenGlobal() {
    AgentAwareProcessStarter starter = starter(name -> new Profile.Sandbox("local", "1g", null));

    ExecutionBackendProperties effective = starter.resolve("ops-agent");
    assertEquals("local", effective.backend());
    assertEquals("1g", effective.memory()); // 覆写
    assertEquals("1.0", effective.cpus()); // 未覆写继承
    assertEquals("alpine:3.20", effective.image()); // 镜像不可覆写（治理域）
    assertEquals("none", effective.network()); // 网络不可覆写
  }

  @Test
  @DisplayName("收敛_段缺省或Agent未知_完全继承全局")
  void missingSandboxInheritsGlobal() {
    AgentAwareProcessStarter nullLookup = starter(name -> null);
    assertSame(
        starter(name -> null).resolve(null).backend(), nullLookup.resolve("anyone").backend());

    // 全局 local + Agent 无声明 → local（现状零变化路径）
    AgentAwareProcessStarter globalLocal =
        new AgentAwareProcessStarter(
            new ExecutionBackendProperties(null, null, null, null, null, null),
            name -> null,
            local,
            props -> docker.withProps(props));
    assertEquals("local", globalLocal.resolve("someone").backend());
  }

  @Test
  @DisplayName("路由_全局docker_单Agent覆写local_local档执行")
  void routingHonorsAgentOverride() throws Exception {
    ToolExecutionContext.setAgentName("ops-agent");
    AgentAwareProcessStarter starter = starter(name -> new Profile.Sandbox("local", null, null));

    starter.start(List.of("ls"));

    assertEquals(1, local.started);
    assertEquals(0, docker.invocations.size());
    assertNull(ToolExecutionContext.executionBackend(), "local 路径不置审计上下文（现状语义）");
  }

  @Test
  @DisplayName("路由_全局local_单Agent覆写docker_该Agent走容器")
  void routingAgentEscalatesToDocker() throws Exception {
    ToolExecutionContext.setAgentName("risky-agent");
    AgentAwareProcessStarter starter =
        new AgentAwareProcessStarter(
            new ExecutionBackendProperties(null, "alpine:3.20", null, null, null, null),
            name -> "risky-agent".equals(name) ? new Profile.Sandbox("docker", "256m", null) : null,
            local,
            props -> docker.withProps(props));

    starter.start(List.of("ls"));

    assertEquals(0, local.started);
    assertEquals(1, docker.invocations.size());
    assertEquals("256m", docker.invocations.get(0).memory(), "Agent 限额覆写传入 docker 档");
  }

  @Test
  @DisplayName("路由_两个Agent不同声明_各走各档（SC-008 选档面）")
  void twoAgentsRouteDifferently() throws Exception {
    AgentAwareProcessStarter starter =
        new AgentAwareProcessStarter(
            new ExecutionBackendProperties("docker", "alpine:3.20", null, null, null, null),
            name -> "local-agent".equals(name) ? new Profile.Sandbox("local", null, null) : null,
            local,
            props -> docker.withProps(props));

    ToolExecutionContext.setAgentName("local-agent");
    starter.start(List.of("ls"));
    ToolExecutionContext.setAgentName("docker-agent");
    starter.start(List.of("ls"));

    assertEquals(1, local.started);
    assertEquals(1, docker.invocations.size());
  }

  private static final class RecordingLocal implements ProcessStarter {
    int started;

    @Override
    public Process start(List<String> command) {
      started++;
      return new DockerProcessStarterTest.FakeCliProcess();
    }
  }

  private static final class RecordingDocker implements ProcessStarter {
    final List<ExecutionBackendProperties> invocations = new java.util.ArrayList<>();

    ProcessStarter withProps(ExecutionBackendProperties props) {
      invocations.add(props);
      return this;
    }

    @Override
    public Process start(List<String> command) {
      return new DockerProcessStarterTest.FakeCliProcess();
    }
  }
}
