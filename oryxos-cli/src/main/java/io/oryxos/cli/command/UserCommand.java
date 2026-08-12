package io.oryxos.cli.command;

import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.storage.WebUser;
import io.oryxos.storage.WebUserService;
import java.io.Console;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * 重命令组：管理台账号管理（012-web-auth）。每个 leaf 自启 Spring（镜像 {@link ChatCommand}）， 通过 {@code
 * context.getBean(WebUserService.class)} 干活。密码输入不回显（{@link Console#readPassword}）。
 */
@Command(
    name = "user",
    description = "管理管理台账号（Basic Auth）",
    mixinStandardHelpOptions = true,
    subcommands = {
      UserCommand.AddCommand.class,
      UserCommand.ListCommand.class,
      UserCommand.DeleteCommand.class,
      UserCommand.PasswdCommand.class,
      UserCommand.DisableCommand.class,
      UserCommand.EnableCommand.class
    })
public class UserCommand implements Runnable {

  /** 密码最小长度（P3C：提常量避免魔法值）。 */
  private static final int MIN_PASSWORD_LENGTH = 8;

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }

  /** 起一次性 Spring 上下文，跑完即退。 */
  private static void withService(java.util.function.Consumer<WebUserService> action) {
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(OryxOsRuntime.class)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF)
            .run()) {
      action.accept(context.getBean(WebUserService.class));
    }
  }

  /** 读密码（不回显）；两次一致且 ≥8 字符返回，否则抛 IllegalStateException。无 console 报错。 */
  private static String readConfirmedPassword() {
    Console console = System.console();
    if (console == null) {
      throw new IllegalStateException("no interactive console available (stdin piped?)");
    }
    char[] first = console.readPassword("Password (>= 8 chars): ");
    char[] confirm = console.readPassword("Confirm: ");
    String a = first == null ? "" : new String(first);
    String b = confirm == null ? "" : new String(confirm);
    if (first != null) {
      java.util.Arrays.fill(first, '\0');
    }
    if (confirm != null) {
      java.util.Arrays.fill(confirm, '\0');
    }
    if (!a.equals(b)) {
      throw new IllegalStateException("passwords do not match");
    }
    if (a.length() < MIN_PASSWORD_LENGTH) {
      throw new IllegalStateException("password must be at least 8 characters");
    }
    return a;
  }

  @Command(name = "add", description = "创建账号（交互输密码）", mixinStandardHelpOptions = true)
  static class AddCommand implements Runnable {
    @Parameters(index = "0", description = "用户名（≤64 字符，无空格）")
    String username;

    @Override
    public void run() {
      String password = readConfirmedPassword();
      withService(
          service -> {
            service.create(username, password);
            System.out.println("Created user '" + username + "'");
          });
    }
  }

  @Command(name = "list", description = "列出账号（不显密码）", mixinStandardHelpOptions = true)
  static class ListCommand implements Runnable {
    @Override
    public void run() {
      withService(
          service -> {
            List<WebUser> users = service.list();
            if (users.isEmpty()) {
              System.out.println("No users found. Run 'oryxos user add <username>' to create one.");
              return;
            }
            DateTimeFormatter fmt = DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.systemDefault());
            System.out.printf("%-24s %-8s %s%n", "USERNAME", "ENABLED", "CREATED_AT");
            for (WebUser u : users) {
              System.out.printf(
                  "%-24s %-8s %s%n",
                  u.getUsername(),
                  u.isEnabled(),
                  u.getCreatedAt() == null ? "" : fmt.format(u.getCreatedAt()));
            }
          });
    }
  }

  @Command(name = "delete", description = "删除账号", mixinStandardHelpOptions = true)
  static class DeleteCommand implements Runnable {
    @Parameters(index = "0", description = "用户名")
    String username;

    @Override
    public void run() {
      withService(
          service -> {
            service.delete(username);
            System.out.println("Deleted user '" + username + "'");
          });
    }
  }

  @Command(name = "passwd", description = "改密码（交互输新密码）", mixinStandardHelpOptions = true)
  static class PasswdCommand implements Runnable {
    @Parameters(index = "0", description = "用户名")
    String username;

    @Override
    public void run() {
      String password = readConfirmedPassword();
      withService(
          service -> {
            service.changePassword(username, password);
            System.out.println("Password updated for '" + username + "'");
          });
    }
  }

  @Command(name = "disable", description = "禁用账号", mixinStandardHelpOptions = true)
  static class DisableCommand implements Runnable {
    @Parameters(index = "0", description = "用户名")
    String username;

    @Override
    public void run() {
      withService(
          service -> {
            service.disable(username);
            System.out.println("Disabled user '" + username + "'");
          });
    }
  }

  @Command(name = "enable", description = "启用账号", mixinStandardHelpOptions = true)
  static class EnableCommand implements Runnable {
    @Parameters(index = "0", description = "用户名")
    String username;

    @Override
    public void run() {
      withService(
          service -> {
            service.enable(username);
            System.out.println("Enabled user '" + username + "'");
          });
    }
  }
}
