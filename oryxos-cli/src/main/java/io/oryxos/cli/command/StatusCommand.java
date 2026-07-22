package io.oryxos.cli.command;

import java.nio.file.Files;
import java.nio.file.Path;
import picocli.CommandLine.Command;

/** 轻命令：看一眼工作区与数据文件的状态，不启动 Spring（课件坑二：看一眼就退的命令必须秒回）。 */
@Command(name = "status", description = "查看工作区与运行状态", mixinStandardHelpOptions = true)
public class StatusCommand implements Runnable {

  @Override
  public void run() {
    Path root = Workspace.root();
    System.out.println(
        "工作区 " + root + "/  : " + (Files.isDirectory(root) ? "已初始化" : "未初始化（先跑 oryxos init）"));
    System.out.println("Agent 目录      : " + describeDir(root.resolve("agents"), "*"));
    System.out.println(
        "SQLite 数据库   : "
            + (Files.exists(Path.of("oryxos.db")) ? "oryxos.db 存在" : "尚未创建（首次重命令运行时生成）"));
  }

  private static String describeDir(Path dir, String glob) {
    if (!Files.isDirectory(dir)) {
      return "不存在";
    }
    try (var files = Files.newDirectoryStream(dir, glob)) {
      int count = 0;
      var iterator = files.iterator();
      while (iterator.hasNext()) {
        iterator.next();
        count++;
      }
      return count + " 个";
    } catch (java.io.IOException e) {
      return "读取失败: " + e.getMessage();
    }
  }
}
