package io.oryxos.tool.builtin;

import io.oryxos.tool.sandbox.ActionType;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 内置 Shell 工具：执行 bash 命令，带超时兜底——命令挂死不能拖死整个 ReAct 循环。
 *
 * <p>命令首词白名单归 24 节（SHELL_COMMAND 检查位已过 enforce，含命令注入元字符扫描）；超时默认 30 秒。 两个运维细节（review 高危 6）： (1)
 * stdout/stderr 在 waitFor 前就并发排空——否则输出超过管道缓冲（~64KB）的命令会写阻塞、被误判超时； (2) 超时后递归杀进程树——{@code bash -c}
 * 派生的孙进程不在 bash 的进程组内，只杀 bash 会留孤儿继续跑。
 */
public class ShellTools {

  /** 默认超时：30 秒（clarify 既定默认）。 */
  static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  /** 排空子进程输出的虚拟线程执行器（宪法 VII）：流读取是 IO 等待，虚拟线程天然适配。 */
  @SuppressWarnings("PMD.ThreadPoolCreationRule") // Java 21 虚拟线程无池参数可配，非 P3C 针对的固定线程池反模式。
  private static final ExecutorService DRAINER = Executors.newVirtualThreadPerTaskExecutor();

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
          "shell 工具的功能本质就是执行 LLM 给出的命令；命令白名单由首行 Sandbox.enforce 前置校验（24 节 WhitelistSandbox，含元字符扫描）")
  public String shell(@ToolParam(description = "要执行的 bash 命令") String command) {
    sandbox.enforce(new SandboxAction(ActionType.SHELL_COMMAND, command));
    Process process;
    try {
      process = new ProcessBuilder("bash", "-c", command).start();
    } catch (IOException e) {
      throw new UncheckedIOException("命令启动失败: " + command, e);
    }
    // 先起并发排空再 waitFor：管道不被写满阻塞，waitFor 只在"命令真没跑完"时超时
    Future<byte[]> stdout = DRAINER.submit(() -> process.getInputStream().readAllBytes());
    Future<byte[]> stderr = DRAINER.submit(() -> process.getErrorStream().readAllBytes());
    boolean finished;
    try {
      finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      killTree(process);
      throw new IllegalStateException("命令执行被中断: " + command, e);
    }
    if (!finished) {
      killTree(process);
      throw new IllegalStateException("命令超时（" + timeout.toSeconds() + "s）被终止: " + command);
    }
    try {
      if (process.exitValue() != 0) {
        String err = new String(stderr.get(), StandardCharsets.UTF_8);
        throw new IllegalStateException("命令退出码 " + process.exitValue() + ": " + err.trim());
      }
      return new String(stdout.get(), StandardCharsets.UTF_8);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("命令执行被中断: " + command, e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("命令输出读取失败: " + command, e.getCause());
    }
  }

  /** 先递归杀 bash 派生的子孙进程，再杀 bash 本身（只 destroyForcibly(bash) 会留孤儿继续执行）。 */
  private static void killTree(Process process) {
    process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
  }
}
