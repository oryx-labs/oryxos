package io.oryxos.cli.command;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * 轻命令组：Agent 目录管理（一个目录 = 一个 Agent，第 29 节）。直接读写 {@code .oryxos/agents/<name>/AGENT.md}， 零
 * Spring（课件：列个目录不值得等 4 秒）。
 */
@Command(
    name = "profile",
    description = "管理 Agent（.oryxos/agents/ 下一个目录一个 Agent）",
    mixinStandardHelpOptions = true,
    subcommands = {
      ProfileCommand.ListCommand.class,
      ProfileCommand.CreateCommand.class,
      ProfileCommand.ShowCommand.class,
      ProfileCommand.DeleteCommand.class
    })
public class ProfileCommand implements Runnable {

  private static final Path AGENTS_DIR = Path.of(".oryxos", "agents");

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }

  static Path agentDir(String name) {
    return AGENTS_DIR.resolve(name);
  }

  static Path agentFile(String name) {
    return agentDir(name).resolve("AGENT.md");
  }

  @Command(name = "list", description = "列出全部 Agent", mixinStandardHelpOptions = true)
  static class ListCommand implements Runnable {
    @Override
    public void run() {
      if (!Files.isDirectory(AGENTS_DIR)) {
        System.out.println("Agent 目录不存在（先跑 oryxos init）。");
        return;
      }
      try (Stream<Path> dirs = Files.list(AGENTS_DIR)) {
        dirs.filter(Files::isDirectory).sorted().forEach(p -> System.out.println(p.getFileName()));
      } catch (IOException e) {
        throw new UncheckedIOException("读取 Agent 目录失败", e);
      }
    }
  }

  @Command(
      name = "create",
      description = "创建 Agent（最小 AGENT.md 模板）",
      mixinStandardHelpOptions = true)
  static class CreateCommand implements Runnable {
    @Parameters(index = "0", description = "Agent 名（= 目录名）")
    String name;

    @Override
    public void run() {
      Path file = agentFile(name);
      if (Files.exists(file)) {
        System.out.println("已存在，未覆盖: " + file);
        return;
      }
      String template =
          """
          ---
          name: {name}
          description: 描述这个 Agent 做什么
          identity:
            agent_name: {name}
            prompt: 你是一个乐于助人的助手。
          provider:
            name: deepseek
            model: deepseek-chat
          tools: []
          bootstrap:
            - AGENTS.md
          settings:
            max_iterations: 10
            max_history_turns: 20
          ---

          在这里写这个 Agent 的任务指令（正文）。被触发时它会照做。
          """
              .replace("{name}", name);
      try {
        Files.createDirectories(agentDir(name));
        Files.writeString(file, template);
      } catch (IOException e) {
        throw new UncheckedIOException("写入 Agent 失败", e);
      }
      System.out.println("已创建: " + file);
    }
  }

  @Command(name = "show", description = "查看 Agent 的 AGENT.md", mixinStandardHelpOptions = true)
  static class ShowCommand implements Runnable {
    @Parameters(index = "0", description = "Agent 名")
    String name;

    @Override
    public void run() {
      Path file = agentFile(name);
      if (!Files.exists(file)) {
        System.err.println("Agent 不存在: " + name);
        return;
      }
      try {
        System.out.println(Files.readString(file));
      } catch (IOException e) {
        throw new UncheckedIOException("读取 Agent 失败", e);
      }
    }
  }

  @Command(name = "delete", description = "删除 Agent（整个目录）", mixinStandardHelpOptions = true)
  static class DeleteCommand implements Runnable {
    @Parameters(index = "0", description = "Agent 名")
    String name;

    @Override
    public void run() {
      Path dir = agentDir(name);
      if (!Files.isDirectory(dir)) {
        System.err.println("Agent 不存在: " + name);
        return;
      }
      try (Stream<Path> walk = Files.walk(dir)) {
        walk.sorted(Comparator.reverseOrder())
            .forEach(
                p -> {
                  try {
                    Files.delete(p);
                  } catch (IOException e) {
                    throw new UncheckedIOException("删除 Agent 失败: " + p.getFileName(), e);
                  }
                });
      } catch (IOException e) {
        throw new UncheckedIOException("删除 Agent 失败", e);
      }
      System.out.println("已删除: " + name);
    }
  }
}
