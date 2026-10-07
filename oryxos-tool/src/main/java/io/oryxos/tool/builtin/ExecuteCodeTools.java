package io.oryxos.tool.builtin;

import io.oryxos.tool.sandbox.ActionType;
import io.oryxos.tool.sandbox.ExecutionBackendProperties;
import io.oryxos.tool.sandbox.ProcessStarter;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Container-backed code runner ({@code execute_code}): runs a short language snippet via the same
 * {@link ProcessStarter} used by {@link ShellTools}. Requires docker execution backend (fail-loud,
 * never silent local fallback). Does not replace {@code shell}.
 */
public class ExecuteCodeTools {

  static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
  static final int MAX_OUTPUT_BYTES = 64 * 1024;
  private static final Set<String> SUPPORTED = Set.of("python3", "python");

  @SuppressWarnings("PMD.ThreadPoolCreationRule")
  private static final ExecutorService DRAINER = Executors.newVirtualThreadPerTaskExecutor();

  private final Sandbox sandbox;
  private final ProcessStarter processStarter;
  private final ExecutionBackendProperties execution;
  private final boolean enabled;
  private final Duration timeout;

  public ExecuteCodeTools(
      Sandbox sandbox,
      ProcessStarter processStarter,
      ExecutionBackendProperties execution,
      boolean enabled) {
    this(sandbox, processStarter, execution, enabled, DEFAULT_TIMEOUT);
  }

  ExecuteCodeTools(
      Sandbox sandbox,
      ProcessStarter processStarter,
      ExecutionBackendProperties execution,
      boolean enabled,
      Duration timeout) {
    this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
    this.processStarter = Objects.requireNonNull(processStarter, "processStarter");
    this.execution = Objects.requireNonNull(execution, "execution");
    this.enabled = enabled;
    this.timeout = Objects.requireNonNull(timeout, "timeout");
  }

  @Tool(
      name = "execute_code",
      description =
          "Run a short code snippet in an isolated docker execution backend (python3). "
              + "Requires oryxos.sandbox.execution.backend=docker. Does not replace shell.")
  public String executeCode(
      @ToolParam(description = "Language runtime; currently only python3") String language,
      @ToolParam(description = "Source code to execute") String code) {
    if (!enabled) {
      throw new IllegalStateException(
          "execute_code is disabled; set oryxos.tool.execute-code.enabled=true");
    }
    // 闸门与执行体同源：starter 按 Agent 现算生效档（024 D8，Agent 覆写 > 全局），
    // 所以这里判定的是本次调用真正会用的档，而不是管理员配的全局档。
    ExecutionBackendProperties effective = processStarter.effectiveBackend();
    if (effective == null) {
      effective = execution;
    }
    if (!effective.isDocker()) {
      throw new IllegalStateException(
          "execute_code requires an effective execution backend of docker, but the effective backend is '"
              + effective.backend()
              + "' (fail-loud; no local fallback)");
    }
    if (execution.image() == null || execution.image().isBlank()) {
      throw new IllegalStateException(
          "execute_code requires oryxos.sandbox.execution.image (e.g. python:3.12-alpine)");
    }
    String lang = language == null ? "" : language.strip().toLowerCase(Locale.ROOT);
    if (!SUPPORTED.contains(lang)) {
      throw new IllegalArgumentException("unsupported execute_code language: " + language);
    }
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("execute_code code must not be blank");
    }
    String executable = "python3";
    sandbox.enforce(new SandboxAction(ActionType.SHELL_COMMAND, executable));
    List<String> command = List.of(executable, "-c", code);
    try {
      Process process = processStarter.start(command);
      Future<Bounded> stdout = DRAINER.submit(() -> drain(process.getInputStream()));
      Future<Bounded> stderr = DRAINER.submit(() -> drain(process.getErrorStream()));
      boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!finished) {
        process.destroyForcibly();
        throw new IllegalStateException(
            "execute_code timed out after " + timeout.toSeconds() + "s");
      }
      int exit = process.exitValue();
      Bounded out = stdout.get(2, TimeUnit.SECONDS);
      Bounded err = stderr.get(2, TimeUnit.SECONDS);
      StringBuilder sb = new StringBuilder();
      sb.append("exit=").append(exit).append('\n');
      sb.append("stdout:\n").append(out.text());
      if (out.truncated()) {
        sb.append("\n[stdout truncated]");
      }
      sb.append("\nstderr:\n").append(err.text());
      if (err.truncated()) {
        sb.append("\n[stderr truncated]");
      }
      return sb.toString();
    } catch (IOException e) {
      throw new IllegalStateException("execute_code failed to start process: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("execute_code interrupted", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("execute_code drain failed: " + e.getCause(), e);
    } catch (java.util.concurrent.TimeoutException e) {
      throw new IllegalStateException("execute_code drain timed out", e);
    }
  }

  private static Bounded drain(InputStream in) throws IOException {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    byte[] chunk = new byte[4096];
    int n;
    boolean truncated = false;
    while ((n = in.read(chunk)) >= 0) {
      if (!truncated) {
        int room = MAX_OUTPUT_BYTES - buf.size();
        if (n <= room) {
          buf.write(chunk, 0, n);
        } else {
          if (room > 0) {
            buf.write(chunk, 0, room);
          }
          truncated = true;
        }
      }
    }
    return new Bounded(buf.toString(StandardCharsets.UTF_8), truncated);
  }

  private record Bounded(String text, boolean truncated) {}
}
