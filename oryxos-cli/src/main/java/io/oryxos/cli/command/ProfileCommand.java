package io.oryxos.cli.command;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/** 轻命令组：Profile 文件管理。直接读写 .oryxos/profiles/，零 Spring（课件：列个目录不值得等 4 秒）。 */
@Command(
    name = "profile",
    description = "管理 Agent Profile",
    mixinStandardHelpOptions = true,
    subcommands = {
      ProfileCommand.ListCommand.class,
      ProfileCommand.CreateCommand.class,
      ProfileCommand.ShowCommand.class,
      ProfileCommand.DeleteCommand.class
    })
public class ProfileCommand implements Runnable {

  private static final Path PROFILES_DIR = Path.of(".oryxos", "profiles");

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }

  static Path profileFile(String name) {
    return PROFILES_DIR.resolve(name + ".yaml");
  }

  @Command(name = "list", description = "列出全部 Profile", mixinStandardHelpOptions = true)
  static class ListCommand implements Runnable {
    @Override
    public void run() {
      if (!Files.isDirectory(PROFILES_DIR)) {
        System.out.println("Profile 目录不存在（先跑 oryxos init）。");
        return;
      }
      try (Stream<Path> files = Files.list(PROFILES_DIR)) {
        files
            .filter(p -> String.valueOf(p.getFileName()).endsWith(".yaml"))
            .sorted()
            .forEach(p -> System.out.println(String.valueOf(p.getFileName()).replace(".yaml", "")));
      } catch (IOException e) {
        throw new UncheckedIOException("读取 Profile 目录失败", e);
      }
    }
  }

  @Command(name = "create", description = "创建 Profile（最小模板）", mixinStandardHelpOptions = true)
  static class CreateCommand implements Runnable {
    @Parameters(index = "0", description = "Profile 名")
    String name;

    @Override
    public void run() {
      Path file = profileFile(name);
      if (Files.exists(file)) {
        System.out.println("已存在，未覆盖: " + file);
        return;
      }
      String template =
          """
          name: {name}
          description: 描述这个 Agent 做什么
          identity:
            agent_name: {name}
            prompt: 你是一个乐于助人的助手。
          provider:
            name: deepseek
            model: deepseek-chat
          tools: []
          skills: []
          bootstrap:
            - AGENTS.md
          settings:
            max_iterations: 10
            max_history_turns: 20
          """
              .replace("{name}", name);
      try {
        Files.createDirectories(PROFILES_DIR);
        Files.writeString(file, template);
      } catch (IOException e) {
        throw new UncheckedIOException("写入 Profile 失败", e);
      }
      System.out.println("已创建: " + file);
    }
  }

  @Command(name = "show", description = "查看 Profile 内容", mixinStandardHelpOptions = true)
  static class ShowCommand implements Runnable {
    @Parameters(index = "0", description = "Profile 名")
    String name;

    @Override
    public void run() {
      Path file = profileFile(name);
      if (!Files.exists(file)) {
        System.err.println("Profile 不存在: " + name);
        return;
      }
      try {
        System.out.println(Files.readString(file));
      } catch (IOException e) {
        throw new UncheckedIOException("读取 Profile 失败", e);
      }
    }
  }

  @Command(name = "delete", description = "删除 Profile", mixinStandardHelpOptions = true)
  static class DeleteCommand implements Runnable {
    @Parameters(index = "0", description = "Profile 名")
    String name;

    @Override
    public void run() {
      try {
        if (Files.deleteIfExists(profileFile(name))) {
          System.out.println("已删除: " + name);
        } else {
          System.err.println("Profile 不存在: " + name);
        }
      } catch (IOException e) {
        throw new UncheckedIOException("删除 Profile 失败", e);
      }
    }
  }
}
