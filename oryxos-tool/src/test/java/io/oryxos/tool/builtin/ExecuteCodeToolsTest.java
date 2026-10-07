package io.oryxos.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.ToolExecutionContext;
import io.oryxos.core.profile.Profile;
import io.oryxos.tool.sandbox.AgentAwareProcessStarter;
import io.oryxos.tool.sandbox.ExecutionBackendProperties;
import io.oryxos.tool.sandbox.LocalProcessStarter;
import io.oryxos.tool.sandbox.PermissiveSandbox;
import io.oryxos.tool.sandbox.ProcessStarter;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExecuteCodeToolsTest {

  @Test
  @DisplayName("disabled: fail loud")
  void disabled_fails() {
    ExecuteCodeTools tools =
        new ExecuteCodeTools(
            new PermissiveSandbox(),
            new LocalProcessStarter(),
            new ExecutionBackendProperties("docker", "python:3.12-alpine", null, null, null, null),
            false);
    assertThrows(IllegalStateException.class, () -> tools.executeCode("python3", "print(1)"));
  }

  @Test
  @DisplayName("non-docker backend: fail loud, no local fallback")
  void localBackend_fails() {
    ExecuteCodeTools tools =
        new ExecuteCodeTools(
            new PermissiveSandbox(),
            new LocalProcessStarter(),
            new ExecutionBackendProperties("local", "", null, null, null, null),
            true);
    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> tools.executeCode("python3", "print(1)"));
    assertTrue(ex.getMessage().contains("docker"));
  }

  @Test
  @DisplayName("docker backend: runs via ProcessStarter argv python3 -c")
  void dockerBackend_invokesStarter() throws Exception {
    AtomicReference<List<String>> seen = new AtomicReference<>();
    ProcessStarter starter =
        command -> {
          seen.set(List.copyOf(command));
          return new ProcessBuilder("python3", "-c", "print('ok')").start();
        };
    ExecuteCodeTools tools =
        new ExecuteCodeTools(
            new PermissiveSandbox(),
            starter,
            new ExecutionBackendProperties("docker", "python:3.12-alpine", null, null, null, null),
            true);
    String out = tools.executeCode("python3", "print('ok')");
    assertTrue(out.contains("exit=0"));
    assertTrue(out.contains("ok"));
    assertTrue(seen.get().equals(List.of("python3", "-c", "print('ok')")));
  }

  @Test
  @DisplayName("agent override to local: fail loud instead of running on the host")
  void agentOverrideToLocal_failsLoudInsteadOfHostExecution() {
    ExecutionBackendProperties global =
        new ExecutionBackendProperties("docker", "python:3.12-alpine", null, null, null, null);
    AtomicBoolean hostStart = new AtomicBoolean();
    ProcessStarter hostStarter =
        command -> {
          hostStart.set(true);
          throw new IOException("host execution must not be reached");
        };
    AgentAwareProcessStarter routing =
        new AgentAwareProcessStarter(
            global,
            name -> new Profile.Sandbox("local", null, null),
            hostStarter,
            effective -> {
              throw new AssertionError("docker backend must not be selected");
            });
    ToolExecutionContext.setAgentName("local-agent");
    try {
      ExecuteCodeTools tools = new ExecuteCodeTools(new PermissiveSandbox(), routing, global, true);
      IllegalStateException ex =
          assertThrows(IllegalStateException.class, () -> tools.executeCode("python3", "print(1)"));
      assertTrue(
          ex.getMessage().contains("docker"),
          "the gate must name the effective backend requirement: " + ex.getMessage());
      assertFalse(hostStart.get(), "execute_code must not fall back to host execution");
    } finally {
      ToolExecutionContext.clear();
    }
  }
}
