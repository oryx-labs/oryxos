package io.oryxos.tool.builtin;

import io.oryxos.tool.sandbox.ActionType;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 内置命令工具：直接执行获准的可执行文件，带超时兜底——命令挂死不能拖死整个 ReAct 循环。
 *
 * <p>白名单校验可执行文件名（SHELL_COMMAND 检查位已过 enforce）；参数作为 argv 直接传给进程，不经 Shell 解释。超时默认 30 秒。两个运维细节： (1)
 * stdout/stderr 在 {@code waitFor} 前就并发排空——否则输出超过管道缓冲（~64KB）的命令会写阻塞、被误判超时；(2)
 * 超时后递归杀进程树——子进程不在父进程组内时，只杀父进程会留孤儿继续跑。
 */
public class ShellTools {

  /** 默认超时：30 秒。 */
  static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  /** 排空子进程输出的虚拟线程执行器（宪法 VII）：流读取是 IO 等待，虚拟线程天然适配。 */
  @SuppressWarnings("PMD.ThreadPoolCreationRule") // Java 21 虚拟线程无池参数可配，非 P3C 针对的固定线程池反模式。
  private static final ExecutorService DRAINER = Executors.newVirtualThreadPerTaskExecutor();

  private final Sandbox sandbox;
  private final Duration timeout;
  private final ProcessStarter processStarter;

  public ShellTools(Sandbox sandbox) {
    this(sandbox, DEFAULT_TIMEOUT);
  }

  ShellTools(Sandbox sandbox, Duration timeout) {
    this(sandbox, timeout, ShellTools::startProcess);
  }

  ShellTools(Sandbox sandbox, Duration timeout, ProcessStarter processStarter) {
    this.sandbox = Objects.requireNonNull(sandbox, "sandbox 不能为空");
    this.timeout = Objects.requireNonNull(timeout, "timeout 不能为空");
    this.processStarter = Objects.requireNonNull(processStarter, "processStarter 不能为空");
  }

  @Tool(name = "shell", description = "执行一个已获许可的可执行文件，返回标准输出")
  public String shell(
      @ToolParam(description = "要执行的、已在白名单中的可执行文件") String executable,
      @ToolParam(description = "传给可执行文件的独立参数数组，不支持 shell 语法") List<String> arguments) {
    String commandExecutable = requireExecutable(executable);
    List<String> command = command(commandExecutable, arguments);
    sandbox.enforce(new SandboxAction(ActionType.SHELL_COMMAND, commandExecutable));
    try {
      Process process = processStarter.start(command);
      // 先起并发排空再 waitFor：管道不被写满阻塞，waitFor 只在「命令真没跑完」时超时
      Future<byte[]> stdout = DRAINER.submit(() -> process.getInputStream().readAllBytes());
      Future<byte[]> stderr = DRAINER.submit(() -> process.getErrorStream().readAllBytes());
      boolean finished;
      try {
        finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        killTree(process);
        throw new IllegalStateException("命令执行被中断: " + commandExecutable, e);
      }
      if (!finished) {
        killTree(process);
        throw new IllegalStateException(
            "命令超时（" + timeout.toSeconds() + "s）被终止: " + commandExecutable);
      }
      try {
        if (process.exitValue() != 0) {
          String err = new String(stderr.get(), StandardCharsets.UTF_8);
          throw new IllegalStateException("命令退出码 " + process.exitValue() + ": " + err.trim());
        }
        return new String(stdout.get(), StandardCharsets.UTF_8);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("命令执行被中断: " + commandExecutable, e);
      } catch (ExecutionException e) {
        throw new IllegalStateException("命令输出读取失败: " + commandExecutable, e.getCause());
      }
    } catch (IOException e) {
      throw new UncheckedIOException("命令启动失败: " + commandExecutable, e);
    }
  }

  /**
   * 默认 ProcessStarter：命名方法而非 lambda，让 SuppressFBWarnings 能落在告警位置上（lambda 编译成 synthetic
   * 方法，构造器上的注解盖不住）。
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "COMMAND_INJECTION",
      justification = "以 argv 直接执行、不经 shell 解释；可执行文件在 shell() 启动前经 Sandbox 精确白名单校验")
  private static Process startProcess(List<String> command) throws IOException {
    return new ProcessBuilder(command).start();
  }

  private static String requireExecutable(String executable) {
    if (executable == null || executable.isBlank()) {
      throw new IllegalArgumentException("可执行文件不能为空");
    }
    return executable.strip();
  }

  private static List<String> command(String executable, List<String> arguments) {
    List<String> command = new ArrayList<>();
    command.add(executable);
    if (arguments != null) {
      for (String argument : arguments) {
        if (argument == null) {
          throw new IllegalArgumentException("命令参数不能为 null");
        }
        command.add(argument);
      }
    }
    return List.copyOf(command);
  }

  @FunctionalInterface
  interface ProcessStarter {
    Process start(List<String> command) throws IOException;
  }

  /** 先递归杀命令派生的子孙进程，再杀命令本身（只 destroyForcibly 主进程会留孤儿继续执行）。 */
  private static void killTree(Process process) {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
  }
}
