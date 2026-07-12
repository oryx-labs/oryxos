package io.oryxos.tool.sandbox;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.sandbox.SandboxWhitelist.Category;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** WhitelistSandbox 的运行时管理（SandboxWhitelist）——增删即刻反映到 enforce，验证白名单从空开始被管理后墙就变。 */
class WhitelistSandboxMutationTest {

  private static WhitelistSandbox emptySandbox() {
    return new WhitelistSandbox(
        new FileSandboxProperties(List.of()),
        new ShellSandboxProperties(List.of()),
        new HttpSandboxProperties(List.of()));
  }

  @Test
  @DisplayName("增加域名后_enforce 立即放行且 list 命中")
  void addDomainThenEnforcePasses() {
    WhitelistSandbox sb = emptySandbox();
    // 加之前 deny-all
    assertThrows(
        SandboxViolationException.class,
        () -> sb.enforce(new SandboxAction(ActionType.HTTP_REQUEST, "https://api.example.com")));

    boolean added = sb.add(Category.HTTP, "*.example.com");

    assertTrue(added);
    assertTrue(sb.list(Category.HTTP).contains("*.example.com"));
    assertDoesNotThrow(
        () -> sb.enforce(new SandboxAction(ActionType.HTTP_REQUEST, "https://api.example.com")));
  }

  @Test
  @DisplayName("删除域名后_enforce 立即拦下")
  void removeDomainThenEnforceBlocks() {
    WhitelistSandbox sb = emptySandbox();
    sb.add(Category.HTTP, "*.example.com");

    boolean removed = sb.remove(Category.HTTP, "*.example.com");

    assertTrue(removed);
    assertThrows(
        SandboxViolationException.class,
        () -> sb.enforce(new SandboxAction(ActionType.HTTP_REQUEST, "https://api.example.com")));
  }

  @Test
  @DisplayName("重复增加幂等_第二次返回 false")
  void addIsIdempotent() {
    WhitelistSandbox sb = emptySandbox();

    assertTrue(sb.add(Category.SHELL, "ls"));
    assertFalse(sb.add(Category.SHELL, "ls"));
    assertEquals(1, sb.list(Category.SHELL).size());
  }

  @Test
  @DisplayName("删除不存在条目_返回 false 不报错")
  void removeNonexistentReturnsFalse() {
    WhitelistSandbox sb = emptySandbox();

    assertFalse(sb.remove(Category.SHELL, "rm"));
  }

  @Test
  @DisplayName("增加文件路径_归一为绝对路径后 enforce 放行")
  void addFileNormalizesPathAndEnforcePasses(@TempDir Path dir) {
    WhitelistSandbox sb = emptySandbox();

    sb.add(Category.FILE, dir.toString());

    // list 返回归一后的绝对路径
    assertTrue(sb.list(Category.FILE).contains(dir.toAbsolutePath().normalize().toString()));
    assertDoesNotThrow(
        () ->
            sb.enforce(
                new SandboxAction(ActionType.FILE_READ, dir.resolve("report.txt").toString())));
    // 白名单外仍被拦
    assertThrows(
        SandboxViolationException.class,
        () -> sb.enforce(new SandboxAction(ActionType.FILE_READ, "/etc/passwd")));
  }

  @Test
  @DisplayName("空值增加_点名拒绝")
  void addBlankRejected() {
    WhitelistSandbox sb = emptySandbox();

    assertThrows(IllegalArgumentException.class, () -> sb.add(Category.HTTP, "  "));
  }
}
