package io.oryxos.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import io.oryxos.tool.sandbox.PermissiveSandbox;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxViolationException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 课件《第20节》验收 harness：ShellToolsTest——正常能跑通 + 越界会被拦 + 超时兜底。 */
class ShellToolsTest {

  private final ShellTools tools = new ShellTools(new PermissiveSandbox());

  @Test
  @DisplayName("shell 正常执行拿到标准输出")
  void shellReturnsStdout() {
    assertEquals("oryx", tools.shell("echo -n oryx"));
  }

  @Test
  @DisplayName("非零退出码_失败带 stderr")
  void nonZeroExitFailsWithStderr() {
    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> tools.shell("echo boom >&2; exit 3"));

    assertTrue(ex.getMessage().contains("3"));
    assertTrue(ex.getMessage().contains("boom"));
  }

  @Test
  @DisplayName("命令挂死_按超时终止并报失败")
  void hangingCommandIsKilledOnTimeout() {
    ShellTools shortTimeout = new ShellTools(new PermissiveSandbox(), Duration.ofMillis(300));

    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> shortTimeout.shell("sleep 5"));

    assertTrue(ex.getMessage().contains("超时"));
  }

  @Test
  @DisplayName("越界会被拦：白名单拒绝时命令根本不跑")
  void sandboxRejectionBlocksCommand() {
    Sandbox denying = mock(Sandbox.class);
    doThrow(new SandboxViolationException("命令不在白名单")).when(denying).enforce(any());

    assertThrows(SandboxViolationException.class, () -> new ShellTools(denying).shell("echo hi"));
  }
}
