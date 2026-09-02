package io.oryxos.channel.cli;

import io.oryxos.core.agent.AgentService;
import io.oryxos.core.agent.StreamListener;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;

/**
 * chat 命令的交互通道：读 stdin、写 stdout，维护当前 Session，每行交给引擎，{@code /quit} 退出、{@code /new} 清空历史。
 *
 * <p>CLI 是消息进出的门，不是干活的人——本类没有任何 Agent 智能，就是读—转交—打印的壳（课件骨架）。 channel 字面量 "cli" 只作为三元组参数提供，session_id
 * 拼接在 SessionManager 内部（H4④）。
 *
 * <p>stdin 编码：有 {@link System#console()} 用 console reader；否则 {@link Charset#defaultCharset()}。不硬编码
 * UTF-8。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "AgentService/SessionManager 均为 Runtime 装配单例，共享引用正是意图")
public class CliChannel {

  private static final String QUIT = "/quit";
  private static final String NEW = "/new";

  private final AgentService agentService;
  private final SessionManager sessionManager;

  public CliChannel(AgentService agentService, SessionManager sessionManager) {
    this.agentService = agentService;
    this.sessionManager = sessionManager;
  }

  public void run(String profileName, String userId) {
    Session session = sessionManager.getOrCreate("cli", userId, profileName);
    PrintStream out = System.out;
    out.printf("已连接 Agent [%s]，输入 %s 退出，%s 开新会话。%n", profileName, QUIT, NEW);
    BufferedReader in = stdinReader();
    while (true) {
      out.print("> ");
      String line = readLine(in);
      if (line == null || QUIT.equals(line.trim())) {
        // EOF（Ctrl-D / 管道结束）等同退出——不抛堆栈
        out.println("再见。");
        return;
      }
      if (line.isBlank()) {
        continue;
      }
      if (NEW.equals(line.trim())) {
        sessionManager.clearHistory(session.sessionId());
        session = sessionManager.getOrCreate("cli", userId, profileName);
        out.println("已开启新会话，之前的对话上下文已清空。");
        continue;
      }
      TypewriterListener listener = new TypewriterListener(out);
      String reply = agentService.process(session, line, listener);
      if (listener.printedAny()) {
        out.println(); // 打字机流结束补换行
      } else {
        out.println(reply); // 无任何 token 流出（如迭代耗尽占位文本）时回落整段输出
      }
    }
  }

  /**
   * 终端打字机（019 FR-015/R8）：token 直打 stdout 并 flush，工具调用期间单行状态提示。 Provider
   * 无流式能力时引擎降级为整段一次回调——终端表现为一次性输出，无需特判。
   */
  static final class TypewriterListener implements StreamListener {

    private final PrintStream out;
    private boolean printedAny;

    TypewriterListener(PrintStream out) {
      this.out = out;
    }

    @Override
    public void onToken(String delta) {
      out.print(delta);
      out.flush();
      printedAny = true;
    }

    @Override
    public void onToolStart(String toolName) {
      if (printedAny) {
        out.println();
      }
      out.printf("[调用工具 %s …]%n", toolName);
    }

    @Override
    public void onToolEnd(String toolName, boolean success) {
      out.printf("[工具 %s %s]%n", toolName, success ? "完成" : "失败");
    }

    boolean printedAny() {
      return printedAny;
    }
  }

  /** 可见给单测：解析 stdin 的 reader 选择策略。 */
  static BufferedReader stdinReader() {
    Console console = System.console();
    if (console != null) {
      return new BufferedReader(console.reader());
    }
    return new BufferedReader(new InputStreamReader(System.in, stdinFallbackCharset()));
  }

  /** 无 {@link System#console()} 时的回退编码——跟 JVM 默认，不写死 UTF-8。 */
  static Charset stdinFallbackCharset() {
    return Charset.defaultCharset();
  }

  private static String readLine(BufferedReader in) {
    try {
      return in.readLine();
    } catch (IOException e) {
      throw new UncheckedIOException("读取终端输入失败", e);
    }
  }
}
