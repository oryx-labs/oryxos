package io.oryxos.tool.builtin;

import io.oryxos.tool.sandbox.ActionType;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 内置 Shell 工具：执行 bash 命令，带超时兜底——命令挂死不能拖死整个 ReAct 循环。
 *
 * <p>命令首词白名单归 24 节（SHELL_COMMAND 检查位已过 enforce）；超时默认 30 秒 （课件未给数值的推定默认，配置化随 24 节 shell 白名单配置一并处理）。
 */
public class ShellTools {

  /** 默认超时：30 秒（clarify 既定默认）。 */
  static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  private final Sandbox sandbox;
  private final Duration timeout;

  public ShellTools(Sandbox sandbox) {
    this(sandbox, DEFAULT_TIMEOUT);
  }

  ShellTools(Sandbox sandbox, Duration timeout) {
    this.sandbox = sandbox;
    this.timeout = timeout;
  }

  @Tool(name = "shell", description = "执行一条 bash 命令，返回标准输出")
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "COMMAND_INJECTION",
      justification =
          "shell 工具的功能本质就是执行 LLM 给出的命令；命令白名单由首行 Sandbox.enforce 前置校验（24 节 WhitelistSandbox）")
  public String shell(@ToolParam(description = "要执行的 bash 命令") String command) {
    sandbox.enforce(new SandboxAction(ActionType.SHELL_COMMAND, command));
    try {
      Process process = new ProcessBuilder("bash", "-c", command).start();
      boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!finished) {
        process.destroyForcibly();
        throw new IllegalStateException("命令超时（" + timeout.toSeconds() + "s）被终止: " + command);
      }
      String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (process.exitValue() != 0) {
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        throw new IllegalStateException("命令退出码 " + process.exitValue() + ": " + stderr.trim());
      }
      return stdout;
    } catch (IOException e) {
      throw new UncheckedIOException("命令启动失败: " + command, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("命令执行被中断: " + command, e);
    }
  }
}
