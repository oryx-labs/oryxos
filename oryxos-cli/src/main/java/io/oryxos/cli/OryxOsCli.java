package io.oryxos.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;

@Command(
    name = "oryxos",
    description = "Enterprise Agent OS — run AI agents on your own infrastructure.",
    mixinStandardHelpOptions = true,
    versionProvider = OryxOsCli.VersionProvider.class,
    subcommands = {CommandLine.HelpCommand.class})
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
