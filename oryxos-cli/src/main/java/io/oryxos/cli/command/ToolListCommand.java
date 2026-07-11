package io.oryxos.cli.command;

import picocli.CommandLine.Command;

/** 轻命令：可用工具清单。20 节 ToolRegistry 就位后改为查注册表；本节输出内置工具规划清单。 */
@Command(
    name = "tool",
    description = "Tool 相关操作",
    mixinStandardHelpOptions = true,
    subcommands = ToolListCommand.ListCommand.class)
public class ToolListCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }

  @Command(name = "list", description = "列出可用 Tool", mixinStandardHelpOptions = true)
  static class ListCommand implements Runnable {
    @Override
    public void run() {
      System.out.println("内置 Tool（第 20 节交付后由 ToolRegistry 提供实时清单）：");
      for (String tool :
          new String[] {
            "read_file",
            "write_file",
            "list_dir",
            "shell",
            "http_get",
            "http_post",
            "save_memory",
            "recall_memory",
            "notify"
          }) {
        System.out.println("  " + tool);
      }
    }
  }
}
