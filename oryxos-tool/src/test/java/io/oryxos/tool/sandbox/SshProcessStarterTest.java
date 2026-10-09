package io.oryxos.tool.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.ToolExecutionContext;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SshProcessStarterTest {

  @AfterEach
  void clear() {
    ToolExecutionContext.clear();
  }

  @Test
  @DisplayName("missing host: fail loud")
  void missingHost_fails() {
    SshProcessStarter starter =
        new SshProcessStarter(new SshExecutionProperties("", "u", 22, "/tmp/key"));
    assertThrows(Exception.class, () -> starter.start(List.of("echo", "hi")));
  }

  @Test
  @DisplayName("builds ssh argv and sets execution backend")
  void buildsArgv() throws Exception {
    AtomicReference<List<String>> seen = new AtomicReference<>();
    ProcessStarter local =
        command -> {
          seen.set(List.copyOf(command));
          return new ProcessBuilder("true").start();
        };
    SshProcessStarter starter =
        new SshProcessStarter(
            new SshExecutionProperties("10.0.0.1", "agent", 2222, "/home/u/.ssh/id"), local);
    starter.start(List.of("uname", "-a"));
    List<String> argv = seen.get();
    assertEquals("ssh", argv.get(0));
    assertTrue(argv.contains("-i"));
    assertTrue(argv.contains("/home/u/.ssh/id"));
    assertTrue(argv.contains("2222"));
    assertTrue(argv.contains("agent@10.0.0.1"));
    // host 之后只应有一个参数：远端命令行本身（逐项已加引号）
    int hostAt = argv.indexOf("agent@10.0.0.1");
    assertEquals(hostAt + 3, argv.size(), "host 之后应只有 -- 与远端命令行： " + argv);
    assertEquals("--", argv.get(hostAt + 1));
    assertEquals("'uname' '-a'", argv.get(hostAt + 2));
    assertEquals("ssh", ToolExecutionContext.executionBackend());
  }

  @Test
  @DisplayName("含 shell 元字符的参数被引起来 —— 远端只收到一个参数，不是两条命令")
  void shellMetacharactersAreQuoted() {
    // 这正是漏洞形态：未转义时远端登录 shell 会把 "/tmp; id" 拆成两条命令
    assertEquals(
        "'ls' '-la' '/tmp; id'",
        SshProcessStarter.remoteCommandLine(List.of("ls", "-la", "/tmp; id")));
    assertEquals(
        "'echo' 'a|b' 'c>d'", SshProcessStarter.remoteCommandLine(List.of("echo", "a|b", "c>d")));
    assertEquals(
        "'sh' '-c' 'rm -rf /'",
        SshProcessStarter.remoteCommandLine(List.of("sh", "-c", "rm -rf /")));
  }

  @Test
  @DisplayName("参数里的单引号被正确闭合再续，不产生逃逸")
  void singleQuoteIsEscaped() {
    // POSIX 单引号内除 ' 外都是字面量；' 用 '\'' 的形式闭合再续
    assertEquals("'a'\\''b'", SshProcessStarter.remoteCommandLine(List.of("a'b")));
    // 还原验证：把转义后的串交给真实的 /bin/sh 解析，参数应原样回来
  }

  @Test
  @DisplayName("转义后的命令行交给真实 sh 解析，参数逐个还原（不经 shell 解释的契约）")
  void quotedLineRoundTripsThroughRealShell() throws Exception {
    for (List<String> argv :
        List.of(
            List.of("ls", "-la", "/tmp; id"),
            List.of("echo", "a'b", "c\"d"),
            List.of("printf", "%s", "$(whoami)`id`"),
            List.of("x", "a b", "c\td"))) {
      String line = SshProcessStarter.remoteCommandLine(argv);
      Process p =
          new ProcessBuilder("/bin/sh", "-c", "printf '%s\\n' " + line)
              .redirectErrorStream(true)
              .start();
      String out =
          new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      p.waitFor();
      String[] got = out.split("\\n", -1);
      // 引号串交给 sh 解析后，应当原样还原【整个】 argv（含 argv[0]）——
      // 远端 shell 正是拿它当命令行执行：argv[0] 是命令，其余是参数。
      for (int i = 0; i < argv.size(); i++) {
        assertEquals(argv.get(i), got[i], "参数 " + i + " 未原样还原: " + argv);
      }
    }
  }

  @Test
  @DisplayName("空参数保留为空参数，不塌掉")
  void emptyArgumentSurvives() {
    assertEquals("'a' '' 'b'", SshProcessStarter.remoteCommandLine(List.of("a", "", "b")));
  }
}
