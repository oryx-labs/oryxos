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
    @DisplayName("白名单内软连接指向外部时，读与不存在目标写均拒绝")
    void symlinkEscapeIsBlocked(@TempDir Path allowed) throws IOException {
      Path outside = Files.createTempDirectory("oryxos-sandbox-outside-");
      Files.writeString(outside.resolve("secret.txt"), "secret");
      Files.createSymbolicLink(allowed.resolve("escape"), outside);
      WhitelistSandbox sb = sandbox(List.of(allowed.toString()), List.of(), List.of());

      assertThrows(
          SandboxViolationException.class,
          () ->
              sb.enforce(
                  new SandboxAction(
                      ActionType.FILE_READ, allowed.resolve("escape/secret.txt").toString())));
      assertThrows(
          SandboxViolationException.class,
          () ->
              sb.enforce(
                  new SandboxAction(
                      ActionType.FILE_WRITE, allowed.resolve("escape/new.txt").toString())));
    }

    @Test
    @DisplayName("合法 Agent Skill 软连接指向同一白名单根时可读")
    void controlledSkillSymlinkIsAllowed(@TempDir Path allowed) throws IOException {
      Path shared = allowed.resolve("skills/report");
      Path local = allowed.resolve("agents/ops/skills");
      Files.createDirectories(shared);
      Files.createDirectories(local);
      Files.writeString(shared.resolve("SKILL.md"), "body");
      Files.createSymbolicLink(local.resolve("report"), Path.of("../../../skills/report"));
      WhitelistSandbox sb = sandbox(List.of(allowed.toString()), List.of(), List.of());

      assertDoesNotThrow(
          () ->
              sb.enforce(
                  new SandboxAction(
                      ActionType.FILE_READ, local.resolve("report/SKILL.md").toString())));
    }

    @Test
    @DisplayName("dangling、多跳逃逸和链接环全部失败关闭")
    void unresolvableLinkShapesAreBlocked(@TempDir Path temp) throws IOException {
      SandboxPathFixture paths = new SandboxPathFixture(temp);
      Path dangling = paths.dangling();
      Path multiHop = paths.multiHopEscape();
      Path cycle = paths.cycle()[0];
      WhitelistSandbox sb = sandbox(List.of(paths.allowed().toString()), List.of(), List.of());

      assertThrows(
          SandboxViolationException.class,
          () -> sb.enforce(new SandboxAction(ActionType.FILE_READ, dangling.toString())));
      assertThrows(
          SandboxViolationException.class,
          () ->
              sb.enforce(
                  new SandboxAction(ActionType.FILE_WRITE, multiHop.resolve("x").toString())));
      assertThrows(
          SandboxViolationException.class,
          () -> sb.enforce(new SandboxAction(ActionType.FILE_READ, cycle.toString())));
    }

    @Test
    @DisplayName("白名单按真实最小根判断，链接的 lexical 位置不能扩大授权")
    void symlinkUsesRealTargetRoot(@TempDir Path workspace) throws IOException {
      Path shared = Files.createDirectories(workspace.resolve("skills/report"));
      Path local = Files.createDirectories(workspace.resolve("agents/ops/skills"));
      Files.writeString(shared.resolve("SKILL.md"), "body");
      Files.createSymbolicLink(local.resolve("report"), Path.of("../../../skills/report"));
      WhitelistSandbox agentOnly = sandbox(List.of(local.toString()), List.of(), List.of());

      assertThrows(
          SandboxViolationException.class,
          () ->
              agentOnly.enforce(
                  new SandboxAction(
                      ActionType.FILE_READ, local.resolve("report/SKILL.md").toString())));
    }
  }

  @Nested
  @DisplayName("Shell 命令白名单")
  class ShellCommandWhitelist {

    private final WhitelistSandbox sb = sandbox(List.of(), List.of("ls", "cat", "echo"), List.of());

    @Test
    @DisplayName("白名单内可执行文件_放行")
    void executableInWhitelistAllowed() {
      assertDoesNotThrow(() -> sb.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "ls")));
    }

    @Test
    @DisplayName("Shell 控制语法不能作为可执行文件绕过白名单")
    void shellSyntaxCannotBypassExecutableWhitelist() {
      assertThrows(
          SandboxViolationException.class,
          () -> sb.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "ls -la; pwd")));
    }

    @Test
    @DisplayName("白名单外命令_拒绝")
    void commandOutsideWhitelistRejected() {
      assertThrows(
          SandboxViolationException.class,
          () -> sb.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "rm -rf /")));
    }

    @Test
    @DisplayName("管理员显式配置的解释器可被 Shell 白名单允许")
    void explicitlyAllowlistedInterpretersAreAllowed() {
      for (String interpreter : List.of("bash", "sh", "cmd.exe", "powershell", "python3", "node")) {
        WhitelistSandbox interpreterSandbox = sandbox(List.of(), List.of(interpreter), List.of());

        assertDoesNotThrow(
            () ->
                interpreterSandbox.enforce(
                    new SandboxAction(ActionType.SHELL_COMMAND, interpreter)));
      }
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

    @Test
    @DisplayName("非 http(s) 即使 host 在白名单也拒绝")
    void nonHttpSchemeRejectedEvenIfHostAllowlisted() {
      for (String url :
          new String[] {
            "ftp://api.deepseek.com/x",
            "file://api.deepseek.com/etc/passwd",
            "ws://api.deepseek.com/socket",
            "data:text/plain,hi"
          }) {
        assertThrows(
            SandboxViolationException.class,
            () -> sb.enforce(new SandboxAction(ActionType.HTTP_REQUEST, url)),
            () -> url);
      }
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
    @DisplayName("web_search 伪目标放行")
    void webSearchPseudoTargetAllowed() {
      assertDoesNotThrow(
          () -> sb.enforce(new SandboxAction(ActionType.HTTP_READ, "web_search:foo")));
    }

    @Test
    @DisplayName("非 http(s) 或无主机名拒绝（不再把 host==null 一律放行）")
    void nonHttpOrHostlessReadBlocked() {
      for (String url :
          new String[] {
            "file:///etc/passwd",
            "file:///C:/Windows/win.ini",
            "data:text/plain,hi",
            "ftp://example.com/x",
            "http:///no-host",
            "https:"
          }) {
        assertThrows(
            SandboxViolationException.class,
            () -> sb.enforce(new SandboxAction(ActionType.HTTP_READ, url)),
            () -> url);
      }
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
            "http://[::ffff:169.254.169.254]/x", // IPv4-mapped 云元数据
            "http://[64:ff9b::a9fe:a9fe]/x", // NAT64 well-known → 169.254.169.254
            "http://[64:ff9b::100.64.1.1]/x", // NAT64 → CGNAT
            "http://[2002:a9fe:a9fe::1]/x", // 6to4 → 169.254.169.254
            "http://[::a9fe:a9fe]/x", // IPv4-compatible → 169.254.169.254
            "http://[2001::5601:5601]/x", // Teredo → 169.254.169.254
            "http://[2001:db8::5efe:a9fe:a9fe]/x", // ISATAP → 169.254.169.254
            "http://[2001:db8::200:5efe:a9fe:a9fe]/x", // ISATAP u-bit → 169.254.169.254
            "http://localhost/x"
          }) {
        assertThrows(
            SandboxViolationException.class,
            () -> sb.enforce(new SandboxAction(ActionType.HTTP_READ, url)),
            () -> "should block: " + url);
      }
    }

    @Test
    @DisplayName("NAT64 / 6to4 嵌入公网 IPv4 仍放行")
    void embeddedPublicIpv4Allowed() {
      assertDoesNotThrow(
          () -> sb.enforce(new SandboxAction(ActionType.HTTP_READ, "http://[64:ff9b::8.8.8.8]/x")));
      assertDoesNotThrow(
          () -> sb.enforce(new SandboxAction(ActionType.HTTP_READ, "http://[2002:808:808::1]/x")));
    }

    @Test
    @DisplayName("Teredo 嵌入公网 IPv4 仍放行")
    void teredoPublicIpv4Allowed() {
      assertDoesNotThrow(
          () -> sb.enforce(new SandboxAction(ActionType.HTTP_READ, "http://[2001::f7f7:f7f7]/x")));
    }

    @Test
    @DisplayName("ISATAP 嵌入公网 IPv4 仍放行")
    void isatapPublicIpv4Allowed() {
      assertDoesNotThrow(
          () ->
              sb.enforce(
                  new SandboxAction(ActionType.HTTP_READ, "http://[2001:db8::5efe:808:808]/x")));
      assertDoesNotThrow(
          () ->
              sb.enforce(
                  new SandboxAction(
                      ActionType.HTTP_READ, "http://[2001:db8::200:5efe:808:808]/x")));
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
