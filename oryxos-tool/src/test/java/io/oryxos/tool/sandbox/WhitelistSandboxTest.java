package io.oryxos.tool.sandbox;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 课件《第24节》验收 harness：WhitelistSandboxTest——安全模块测的重点不是"放行对不对"，而是"绕得过绕不过"。 三类校验各"允许 +
 * 拒绝"成对，再加两个关键绕过场景（路径穿越 normalize 回归、通配符点号边界回归）。
 *
 * <p>只经 {@code enforce(SandboxAction)} 公共入口断言——三个 {@code check*} 是 private，不直测（接口中立性）。
 */
class WhitelistSandboxTest {

  private static WhitelistSandbox sandbox(
      List<String> paths, List<String> commands, List<String> domains) {
    return new WhitelistSandbox(
        new FileSandboxProperties(paths),
        new ShellSandboxProperties(commands),
        new HttpSandboxProperties(domains));
  }

  @Nested
  @DisplayName("文件路径白名单")
  class FilePathWhitelist {

    @Test
    @DisplayName("白名单内路径_读写放行")
    void insideWhitelistAllowed(@TempDir Path allowed) {
      WhitelistSandbox sb = sandbox(List.of(allowed.toString()), List.of(), List.of());
      String inside = allowed.resolve("report.txt").toString();

      assertDoesNotThrow(() -> sb.enforce(new SandboxAction(ActionType.FILE_READ, inside)));
      assertDoesNotThrow(() -> sb.enforce(new SandboxAction(ActionType.FILE_WRITE, inside)));
    }

    @Test
    @DisplayName("白名单外路径_拒绝")
    void outsideWhitelistRejected(@TempDir Path allowed) {
      WhitelistSandbox sb = sandbox(List.of(allowed.toString()), List.of(), List.of());

      assertThrows(
          SandboxViolationException.class,
          () -> sb.enforce(new SandboxAction(ActionType.FILE_READ, "/etc/passwd")));
    }

    @Test
    @DisplayName("空路径_拒绝并给出沙箱异常")
    void nullPathIsRejected(@TempDir Path allowed) {
      WhitelistSandbox sb = sandbox(List.of(allowed.toString()), List.of(), List.of());

      assertThrows(
          SandboxViolationException.class,
          () -> sb.enforce(new SandboxAction(ActionType.FILE_READ, null)));
    }

    @Test
    @DisplayName("相对路径穿越_爬出白名单目录_被拦")
    void relativePathTraversalIsBlocked(@TempDir Path allowed) {
      // 关键回归：normalize 前形似落在白名单内，normalize 后爬到 /etc/passwd——必须在标准化后判定越界
      WhitelistSandbox sb = sandbox(List.of(allowed.toString()), List.of(), List.of());
      String traversal = allowed.resolve("../../../../../../etc/passwd").toString();

      assertThrows(
          SandboxViolationException.class,
          () -> sb.enforce(new SandboxAction(ActionType.FILE_READ, traversal)));
    }

    @Test
    @DisplayName("白名单内文件链接指向外部_读写均拒绝")
    void fileSymlinkEscapingRootIsBlocked(@TempDir Path temp) throws IOException {
      Path allowed = Files.createDirectory(temp.resolve("allowed"));
      Path outside = Files.writeString(temp.resolve("secret.txt"), "secret");
      Path link = Files.createSymbolicLink(allowed.resolve("linked.txt"), outside);
      WhitelistSandbox sb = sandbox(List.of(allowed.toString()), List.of(), List.of());

      assertThrows(
          SandboxViolationException.class,
          () -> sb.enforce(new SandboxAction(ActionType.FILE_READ, link.toString())));
      assertThrows(
          SandboxViolationException.class,
          () -> sb.enforce(new SandboxAction(ActionType.FILE_WRITE, link.toString())));
    }

    @Test
    @DisplayName("白名单内目录链接指向外部_现有文件与新写入路径均拒绝")
    void directorySymlinkEscapingRootIsBlocked(@TempDir Path temp) throws IOException {
      Path allowed = Files.createDirectory(temp.resolve("allowed"));
      Path outside = Files.createDirectory(temp.resolve("outside"));
      Files.writeString(outside.resolve("secret.txt"), "secret");
      Path link = Files.createSymbolicLink(allowed.resolve("linked-dir"), outside);
      WhitelistSandbox sb = sandbox(List.of(allowed.toString()), List.of(), List.of());

      assertThrows(
          SandboxViolationException.class,
          () ->
              sb.enforce(
                  new SandboxAction(ActionType.FILE_READ, link.resolve("secret.txt").toString())));
      assertThrows(
          SandboxViolationException.class,
          () ->
              sb.enforce(
                  new SandboxAction(ActionType.FILE_WRITE, link.resolve("new.txt").toString())));
    }

    @Test
    @DisplayName("白名单内链接仍留在根目录_按真实落点放行")
    void symlinkStayingInsideRootIsAllowed(@TempDir Path allowed) throws IOException {
      Path target = Files.writeString(allowed.resolve("target.txt"), "safe");
      Path link = Files.createSymbolicLink(allowed.resolve("linked.txt"), target.getFileName());
      WhitelistSandbox sb = sandbox(List.of(allowed.toString()), List.of(), List.of());

      assertDoesNotThrow(
          () -> sb.enforce(new SandboxAction(ActionType.FILE_READ, link.toString())));
    }
  }

  @Nested
  @DisplayName("Shell 命令白名单")
  class ShellCommandWhitelist {

    private final WhitelistSandbox sb =
        sandbox(List.of(), List.of("ls", "cat", "echo", "bash", "env"), List.of());

    @Test
    @DisplayName("白名单内命令_首token放行")
    void firstTokenInWhitelistAllowed() {
      assertDoesNotThrow(() -> sb.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "ls -la")));
    }

    @Test
    @DisplayName("白名单外命令_拒绝")
    void commandOutsideWhitelistRejected() {
      assertThrows(
          SandboxViolationException.class,
          () -> sb.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "rm -rf /")));
    }

    @ParameterizedTest(name = "拒绝: {0}")
    @ValueSource(
        strings = {
          "echo ok; touch /tmp/oryxos-pwned",
          "echo ok && touch /tmp/oryxos-pwned",
          "echo ok | cat",
          "echo ok > /tmp/oryxos-pwned",
          "cat < /etc/passwd",
          "echo $(touch /tmp/oryxos-pwned)",
          "echo `touch /tmp/oryxos-pwned`",
          "echo $HOME",
          "echo ok\ntouch /tmp/oryxos-pwned",
          "bash -c 'touch /tmp/oryxos-pwned'",
          "bash -lc 'touch /tmp/oryxos-pwned'",
          "env bash -c 'touch /tmp/oryxos-pwned'",
          "env rm -rf /tmp/oryxos-pwned"
        })
    @DisplayName("复合语法_命令替换_重定向_换行与 shell-c 均拒绝")
    void compoundShellSyntaxIsRejected(String command) {
      assertThrows(
          SandboxViolationException.class,
          () -> sb.enforce(new SandboxAction(ActionType.SHELL_COMMAND, command)));
    }

    @Test
    @DisplayName("带普通参数与引用的单命令仍放行")
    void simpleCommandWithQuotedArgumentIsAllowed() {
      assertDoesNotThrow(
          () -> sb.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "echo 'hello world'")));
    }

    @Test
    @DisplayName("空命令与未闭合引用拒绝并给出沙箱异常")
    void malformedSimpleCommandIsRejected() {
      assertThrows(
          SandboxViolationException.class,
          () -> sb.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "   ")));
      assertThrows(
          SandboxViolationException.class,
          () -> sb.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "echo 'oops")));
    }
  }

  @Nested
  @DisplayName("HTTP 域名白名单")
  class HttpDomainWhitelist {

    private final WhitelistSandbox sb =
        sandbox(List.of(), List.of(), List.of("*.example.com", "api.deepseek.com"));

    @Test
    @DisplayName("通配符命中真子域_精确项命中裸域_放行")
    void allowedDomainsPass() {
      assertDoesNotThrow(
          () ->
              sb.enforce(new SandboxAction(ActionType.HTTP_REQUEST, "https://api.example.com/v1")));
      assertDoesNotThrow(
          () ->
              sb.enforce(
                  new SandboxAction(ActionType.HTTP_REQUEST, "https://a.b.example.com/deep")));
      assertDoesNotThrow(
          () ->
              sb.enforce(
                  new SandboxAction(ActionType.HTTP_REQUEST, "https://api.deepseek.com/v1")));
    }

    @Test
    @DisplayName("通配符域名_命中真子域_不被形似域名绕过")
    void wildcardDomainRespectsDotBoundary() {
      // 关键回归：endsWith("example.com") 的经典漏洞——"evil-example.com".endsWith("example.com") 为真；
      // 匹配逻辑必须带点号边界（.example.com），形似域名与裸域都不得命中
      assertThrows(
          SandboxViolationException.class,
          () ->
              sb.enforce(new SandboxAction(ActionType.HTTP_REQUEST, "http://evil-example.com/x")));
      assertThrows(
          SandboxViolationException.class,
          () -> sb.enforce(new SandboxAction(ActionType.HTTP_REQUEST, "http://example.com/x")));
    }

    @Test
    @DisplayName("畸形URL无主机名_拒绝")
    void malformedUrlWithoutHostRejected() {
      assertThrows(
          SandboxViolationException.class,
          () -> sb.enforce(new SandboxAction(ActionType.HTTP_REQUEST, "not-a-url")));
    }
  }

  @Nested
  @DisplayName("HTTP 读默认放行 + 内网黑名单（第 32 节）")
  class HttpReadDefaultAllow {

    // http 白名单为空也不挡读——读默认放行，只挡 SSRF（内网/回环/云元数据）
    private final WhitelistSandbox sb = sandbox(List.of(), List.of(), List.of());

    @Test
    @DisplayName("读公网地址放行（即使不在白名单）")
    void publicReadAllowed() {
      assertDoesNotThrow(
          () -> sb.enforce(new SandboxAction(ActionType.HTTP_READ, "https://8.8.8.8/x")));
    }

    @Test
    @DisplayName("无主机的伪目标（web_search）放行")
    void hostlessReadAllowed() {
      assertDoesNotThrow(
          () -> sb.enforce(new SandboxAction(ActionType.HTTP_READ, "web_search:foo")));
    }

    @Test
    @DisplayName("读内网/回环/链路本地/localhost 一律拒绝（SSRF）")
    void internalReadBlocked() {
      for (String url :
          new String[] {
            "http://127.0.0.1/x",
            "http://10.1.2.3/x",
            "http://192.168.1.1/x",
            "http://169.254.1.1/x",
            "http://[::1]/x", // IPv6 回环
            "http://[fd00::1]/x", // IPv6 ULA fc00::/7
            "http://localhost/x"
          }) {
        assertThrows(
            SandboxViolationException.class,
            () -> sb.enforce(new SandboxAction(ActionType.HTTP_READ, url)));
      }
    }
  }

  @Nested
  @DisplayName("空白名单 = deny-all")
  class EmptyWhitelistDeniesAll {

    private final WhitelistSandbox sb = sandbox(List.of(), List.of(), List.of());

    @Test
    @DisplayName("三类白名单全空_一律拒绝而非放行")
    void emptyWhitelistRejectsEverything() {
      assertThrows(
          SandboxViolationException.class,
          () -> sb.enforce(new SandboxAction(ActionType.FILE_READ, "/tmp/x")));
      assertThrows(
          SandboxViolationException.class,
          () -> sb.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "ls")));
      assertThrows(
          SandboxViolationException.class,
          () -> sb.enforce(new SandboxAction(ActionType.HTTP_REQUEST, "https://api.example.com")));
    }
  }
}
