package io.oryxos.cli.command;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import picocli.CommandLine.Command;

/** 轻命令：初始化 .oryxos/ 工作区骨架。幂等——已有的目录和文件一律不覆盖（课件 Edge Case）。 */
@Command(name = "init", description = "初始化 .oryxos/ 工作区", mixinStandardHelpOptions = true)
public class InitCommand implements Runnable {

  private static final List<String> DIRS =
      List.of("profiles", "skills", "memory", "sessions", "logs");
  private static final List<String> BOOTSTRAP_FILES = List.of("AGENTS.md", "SOUL.md", "USER.md");

  @Override
  public void run() {
    Path root = Path.of(".oryxos");
    try {
      for (String dir : DIRS) {
        Files.createDirectories(root.resolve(dir));
      }
      for (String file : BOOTSTRAP_FILES) {
        Path target = root.resolve(file);
        if (!Files.exists(target)) {
          Files.writeString(target, "# " + file + "\n");
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("初始化 .oryxos/ 失败", e);
    }
    System.out.println("已初始化 .oryxos/ 工作区（已存在的文件未覆盖）。");
  }
}
