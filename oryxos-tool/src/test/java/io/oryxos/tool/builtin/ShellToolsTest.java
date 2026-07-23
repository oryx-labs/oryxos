package io.oryxos.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import io.oryxos.tool.sandbox.FileSandboxProperties;
import io.oryxos.tool.sandbox.HttpSandboxProperties;
import io.oryxos.tool.sandbox.PermissiveSandbox;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxViolationException;
import io.oryxos.tool.sandbox.ShellSandboxProperties;
import io.oryxos.tool.sandbox.WhitelistSandbox;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

  @Test
  @DisplayName("白名单外命令_起进程前被拦")
  void commandOutsideWhitelist_processNeverStarts() {
    // 真 WhitelistSandbox（只允许 ls），跑 rm——若进程真起了会有副作用；抛 SandboxViolationException 证明起进程前被拦
    Sandbox whitelist =
        new WhitelistSandbox(
            new FileSandboxProperties(List.of()),
            new ShellSandboxProperties(List.of("ls")),
            new HttpSandboxProperties(List.of()));

    assertThrows(
        SandboxViolationException.class,
        () -> new ShellTools(whitelist).shell("rm -rf /tmp/oryxos-should-never-run"));
  }

  @Test
  @DisplayName("首命令虽在白名单_分号追加命令仍在进程启动前拦截")
  void allowedFirstTokenCannotAppendSecondCommand(@TempDir Path temp) {
    Sandbox whitelist = shellWhitelist("echo");
    Path marker = temp.resolve("should-not-exist");

    assertThrows(
        SandboxViolationException.class,
        () -> new ShellTools(whitelist).shell("echo ok; touch " + marker));
    assertFalse(Files.exists(marker));
  }

  @Test
  @DisplayName("bash 本身在白名单_bash-c 仍不能执行嵌套命令")
  void whitelistedBashCannotUseCommandMode(@TempDir Path temp) {
    Sandbox whitelist = shellWhitelist("bash");
    Path marker = temp.resolve("should-not-exist");

    assertThrows(
        SandboxViolationException.class,
        () -> new ShellTools(whitelist).shell("bash -c 'touch " + marker + "'"));
    assertFalse(Files.exists(marker));
  }

  @Test
  @DisplayName("真实白名单下简单单命令仍可执行")
  void simpleWhitelistedCommandStillRuns() {
    assertEquals("oryx", new ShellTools(shellWhitelist("echo")).shell("echo -n oryx"));
  }

  private Sandbox shellWhitelist(String command) {
    return new WhitelistSandbox(
        new FileSandboxProperties(List.of()),
        new ShellSandboxProperties(List.of(command)),
        new HttpSandboxProperties(List.of()));
  }
}
