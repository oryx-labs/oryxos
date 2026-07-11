package io.oryxos.tool.interaction;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * CLI 场景的交互实现：把问题打到终端、读用户输入的一行。
 *
 * <p>与 chat 循环共用同一终端；因 ReAct 循环同步执行，ask_user 期间的读行不会与对话读行并发争抢。
 */
public class ConsoleUserInteraction implements UserInteraction {

  private final BufferedReader in;
  private final PrintStream out;

  public ConsoleUserInteraction() {
    this(System.in, System.out);
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "交互实现必须持有输出流引用向用户打印问题；流的生命周期由调用方（终端 / 测试）管理")
  public ConsoleUserInteraction(InputStream in, PrintStream out) {
    this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    this.out = out;
  }

  @Override
  public String ask(String question) {
    out.println("[Agent 提问] " + question);
    out.print("> ");
    try {
      String line = in.readLine();
      if (line == null) {
        throw new InteractionUnavailableException("输入流已结束，无法获取用户回答");
      }
      return line;
    } catch (IOException e) {
      throw new UncheckedIOException("读取用户回答失败", e);
    }
  }
}
