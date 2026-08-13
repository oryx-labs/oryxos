package io.oryxos.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.tool.sandbox.FileSandboxProperties;
import io.oryxos.tool.sandbox.HttpSandboxProperties;
import io.oryxos.tool.sandbox.PermissiveSandbox;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxViolationException;
import io.oryxos.tool.sandbox.ShellSandboxProperties;
import io.oryxos.tool.sandbox.WhitelistSandbox;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ShellTools 的回归测试：覆盖结构化 argv、Sandbox 拦截、退出码与超时行为。 */
class ShellToolsTest {

  @Test
  @DisplayName("shell 将可执行文件与参数原样作为 argv 传给进程")
  void shellPassesExecutableAndLiteralArgumentsToProcess() {
    AtomicReference<List<String>> startedCommand = new AtomicReference<>();
    ShellTools tools =
        new ShellTools(
            new PermissiveSandbox(),
            Duration.ofSeconds(1),
            command -> {
              startedCommand.set(command);
              return new StubProcess(true, "oryx", "", 0);
            });

    assertEquals("oryx", tools.shell("echo", List.of("-n", "oryx; pwd")));
    assertEquals(List.of("echo", "-n", "oryx; pwd"), startedCommand.get());
  }

  @Test
  @DisplayName("非零退出码_失败带 stderr")
  void nonZeroExitFailsWithStderr() {
    ShellTools tools = shellTools(new StubProcess(true, "", "boom", 3));

    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> tools.shell("echo", List.of("boom")));

    assertTrue(ex.getMessage().contains("3"));
    assertTrue(ex.getMessage().contains("boom"));
  }

  @Test
  @DisplayName("命令挂死_按超时终止并报失败")
  void hangingCommandIsKilledOnTimeout() {
    StubProcess process = new StubProcess(false, "", "", 0);
    ShellTools shortTimeout =
        new ShellTools(new PermissiveSandbox(), Duration.ofMillis(300), command -> process);

    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> shortTimeout.shell("sleep", List.of("5")));

    assertTrue(ex.getMessage().contains("超时"));
    assertTrue(process.wasForciblyDestroyed());
  }

  @Test
  @DisplayName("越界会被拦：白名单拒绝时命令根本不跑")
  void sandboxRejectionBlocksCommand() {
    Sandbox denying =
        action -> {
          throw new SandboxViolationException("命令不在白名单");
        };
    ShellTools tools =
        new ShellTools(
            denying,
            Duration.ofSeconds(1),
            command -> {
              throw new AssertionError("Sandbox 拒绝后不能启动进程");
            });

    assertThrows(SandboxViolationException.class, () -> tools.shell("echo", List.of("hi")));
  }

  @Test
  @DisplayName("白名单外可执行文件_起进程前被拦")
  void executableOutsideWhitelistProcessNeverStarts() {
    Sandbox whitelist =
        new WhitelistSandbox(
            new FileSandboxProperties(List.of()),
            new ShellSandboxProperties(List.of("ls")),
            new HttpSandboxProperties(List.of()));
    ShellTools tools =
        new ShellTools(
            whitelist,
            Duration.ofSeconds(1),
            command -> {
              throw new AssertionError("白名单外可执行文件不能启动进程");
            });

    assertThrows(
        SandboxViolationException.class,
        () -> tools.shell("rm", List.of("-rf", "/tmp/oryxos-should-never-run")));
  }

  private static ShellTools shellTools(Process process) {
    return new ShellTools(new PermissiveSandbox(), Duration.ofSeconds(1), command -> process);
  }

  /** 用最小进程替身隔离操作系统进程，断言 ShellTools 自己的参数与生命周期逻辑。 */
  private static final class StubProcess extends Process {
    private final boolean finished;
    private final byte[] stdout;
    private final byte[] stderr;
    private final int exitCode;
    private boolean forciblyDestroyed;

    private StubProcess(boolean finished, String stdout, String stderr, int exitCode) {
      this.finished = finished;
      this.stdout = stdout.getBytes(StandardCharsets.UTF_8);
      this.stderr = stderr.getBytes(StandardCharsets.UTF_8);
      this.exitCode = exitCode;
    }

    @Override
    public OutputStream getOutputStream() {
      return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(stdout);
    }

    @Override
    public InputStream getErrorStream() {
      return new ByteArrayInputStream(stderr);
    }

    @Override
    public int waitFor() {
      return exitCode;
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) {
      return finished;
    }

    @Override
    public int exitValue() {
      return exitCode;
    }

    @Override
    public void destroy() {
      forciblyDestroyed = true;
    }

    @Override
    public Process destroyForcibly() {
      forciblyDestroyed = true;
      return this;
    }

    @Override
    public java.util.stream.Stream<ProcessHandle> descendants() {
      return java.util.stream.Stream.empty(); // 替身无真实 OS 进程，killTree 无子孙可杀
    }

    private boolean wasForciblyDestroyed() {
      return forciblyDestroyed;
    }
  }
}
