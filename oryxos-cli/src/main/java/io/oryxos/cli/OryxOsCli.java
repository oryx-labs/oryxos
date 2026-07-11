package io.oryxos.cli;

import io.oryxos.cli.command.ChatCommand;
import io.oryxos.cli.command.GatewayCommand;
import io.oryxos.cli.command.InitCommand;
import io.oryxos.cli.command.ProfileCommand;
import io.oryxos.cli.command.ProviderListCommand;
import io.oryxos.cli.command.ServeCommand;
import io.oryxos.cli.command.SessionListCommand;
import io.oryxos.cli.command.StatusCommand;
import io.oryxos.cli.command.ToolListCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;

/**
 * OryxOS 命令行主入口：整个程序的 main，12 个子命令都挂在这（18 节）。
 *
 * <p>CLI 是消息进出的门，不是干活的人——参数解析/帮助/报错全交 Picocli，Agent 逻辑全在引擎。
 */
@Command(
    name = "oryxos",
    description = "Enterprise Agent OS — run AI agents on your own infrastructure.",
    mixinStandardHelpOptions = true,
    versionProvider = OryxOsCli.VersionProvider.class,
    subcommands = {
      CommandLine.HelpCommand.class,
      InitCommand.class,
      StatusCommand.class,
      ChatCommand.class,
      ServeCommand.class,
      GatewayCommand.class,
      ProfileCommand.class,
      ProviderListCommand.class,
      ToolListCommand.class,
      SessionListCommand.class
    })
public class OryxOsCli implements Runnable {

  public static void main(String[] args) {
    int exitCode = new CommandLine(new OryxOsCli()).execute(args);
    System.exit(exitCode);
  }

  @Override
  public void run() {
    new CommandLine(this).usage(System.out);
  }

  static class VersionProvider implements IVersionProvider {
    private static final String DEFAULT_VERSION = "1.0.0-SNAPSHOT";

    @Override
    public String[] getVersion() {
      String ver = OryxOsCli.class.getPackage().getImplementationVersion();
      if (ver == null) {
        ver = DEFAULT_VERSION;
      }
      return new String[] {
        "OryxOS " + ver,
        "JVM: "
            + System.getProperty("java.version")
            + " ("
            + System.getProperty("java.vendor")
            + ")",
        "OS:  " + System.getProperty("os.name") + " " + System.getProperty("os.version")
      };
    }
  }
}
