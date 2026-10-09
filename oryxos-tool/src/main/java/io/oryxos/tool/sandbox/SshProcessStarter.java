package io.oryxos.tool.sandbox;

import io.oryxos.core.agent.ToolExecutionContext;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Remote shell via local {@code ssh} CLI (zero new deps). Does not sync workspace paths — remote
 * cwd/path semantics are the operator's responsibility (documented). Fail-loud when host/key
 * missing.
 */
public final class SshProcessStarter implements ProcessStarter {

  private final SshExecutionProperties props;
  private final ProcessStarter localCli;

  public SshProcessStarter(SshExecutionProperties props) {
    this(props, new LocalProcessStarter());
  }

  SshProcessStarter(SshExecutionProperties props, ProcessStarter localCli) {
    this.props = Objects.requireNonNull(props, "props");
    this.localCli = Objects.requireNonNull(localCli, "localCli");
  }

  @Override
  public Process start(List<String> command) throws IOException {
    return start(command, null);
  }

  @Override
  public Process start(List<String> command, Path workingDirectory) throws IOException {
    if (!props.configured()) {
      throw new IOException(
          "ssh execution backend requires oryxos.sandbox.execution.ssh.host (fail-loud; no local fallback)");
    }
    if (props.identityFile().isBlank()) {
      throw new IOException(
          "ssh execution backend requires oryxos.sandbox.execution.ssh.identity-file");
    }
    // workingDirectory is intentionally ignored: remote filesystem is not the local workspace.
    List<String> argv = new ArrayList<>();
    argv.add("ssh");
    argv.add("-i");
    argv.add(props.identityFile());
    argv.add("-p");
    argv.add(Integer.toString(props.port()));
    argv.add("-o");
    argv.add("BatchMode=yes");
    argv.add("-o");
    argv.add("StrictHostKeyChecking=accept-new");
    argv.add(props.user() + "@" + props.host());
    argv.add("--");
    // OpenSSH 把 host 之后的参数【用空格拼成一个字符串】交给远端登录 shell 重新解析（man ssh：
    // "the arguments will be appended to the command, separated by spaces, before it is sent to
    // the server to be executed"）。逐参数原样传过去，一个含 ";" 的参数就会在远端展开成两条命令
    // ——而 ProcessStarter 的契约是「argv 形式、不经 shell 解释」，shell 白名单也只校验 argv[0]。
    // 这里把每个参数各自引起来，远端 shell 会把它们还原成原来的单个参数。
    argv.add(remoteCommandLine(command));
    ToolExecutionContext.setExecution("ssh", () -> props.user() + "@" + props.host());
    return localCli.start(argv);
  }

  /**
   * 把 argv 拼成一行远端 shell 命令，逐项做 POSIX 单引号转义。
   *
   * <p>sshd 那一侧仍要经登录 shell 执行，所以这层转义是必须的：单引号内除了 {@code '} 本身都是 字面量，{@code '} 用 {@code '\''}
   * 的形式闭合再续，展开后仍是原来那一个参数。
   */
  static String remoteCommandLine(List<String> argv) {
    StringBuilder out = new StringBuilder();
    for (String arg : argv) {
      if (out.length() > 0) {
        out.append(' ');
      }
      out.append('\'').append(arg.replace("'", "'\\''")).append('\'');
    }
    return out.toString();
  }
}
