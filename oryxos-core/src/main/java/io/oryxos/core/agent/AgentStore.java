package io.oryxos.core.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Agent 目录的文件读写，限定在 {@code .oryxos/} 内（第 30 节）。
 *
 * <p>write：把一段 {@code AGENT.md} 写进 {@code .oryxos/agents/<name>/}；delete：回滚删已写目录； archive：把整个 Agent
 * 目录移进 {@code .oryxos/archive/}（删除不物理删，定义可追溯）。 name 必须是安全目录段（防路径穿越）。
 */
public class AgentStore {

  private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_-]+");
  private static final String AGENT_FILE = "AGENT.md";

  private final Path agentsDir;
  private final Path archiveDir;

  public AgentStore(Path oryxosRoot) {
    this.agentsDir = oryxosRoot.resolve("agents");
    this.archiveDir = oryxosRoot.resolve("archive");
  }

  /** 写 .oryxos/agents/&lt;name&gt;/AGENT.md，返回该 Agent 目录。 */
  public Path write(String name, String agentMarkdown) {
    Path dir = agentsDir.resolve(safe(name));
    try {
      Files.createDirectories(dir);
      Files.writeString(dir.resolve(AGENT_FILE), agentMarkdown);
    } catch (IOException e) {
      throw new UncheckedIOException("写入 Agent 目录失败: " + name, e);
    }
    return dir;
  }

  /**
   * 脚手架式写入整个 Agent 目录：{@code files} 的键是相对 Agent 目录的路径（如 {@code AGENT.md}、{@code
   * scripts/example.py}），值是文件内容。每个路径 normalize 后必须落在该 Agent 目录内（防穿越）。返回该 Agent 目录。
   */
  public Path writeAll(String name, Map<String, String> files) {
    Path dir = agentsDir.resolve(safe(name)).normalize();
    try {
      Files.createDirectories(dir);
      for (Map.Entry<String, String> entry : files.entrySet()) {
        Path target = dir.resolve(entry.getKey()).normalize();
        if (!target.startsWith(dir)) {
          throw new IllegalArgumentException("非法文件路径: " + entry.getKey());
        }
        Path parent = target.getParent();
        if (parent != null) {
          Files.createDirectories(parent);
        }
        Files.writeString(target, entry.getValue());
      }
    } catch (IOException e) {
      throw new UncheckedIOException("写入 Agent 目录失败: " + name, e);
    }
    return dir;
  }

  /** 递归删除一个 Agent 目录（create 中途失败回滚用）。 */
  public void delete(Path agentDir) {
    if (!Files.exists(agentDir)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(agentDir)) {
      walk.sorted(Comparator.reverseOrder()).forEach(AgentStore::deleteOne);
    } catch (IOException e) {
      throw new UncheckedIOException("删除 Agent 目录失败: " + agentDir.getFileName(), e);
    }
  }

  /** 整个 Agent 目录移入 .oryxos/archive/（不物理删）；目标已存在则加时间戳后缀避免覆盖。 */
  public void archive(String name) {
    Path src = agentsDir.resolve(safe(name));
    try {
      Files.createDirectories(archiveDir);
      Path dst = archiveDir.resolve(name);
      if (Files.exists(dst)) {
        dst = archiveDir.resolve(name + "-" + System.currentTimeMillis());
      }
      Files.move(src, dst);
    } catch (IOException e) {
      throw new UncheckedIOException("归档 Agent 目录失败: " + name, e);
    }
  }

  private static void deleteOne(Path path) {
    try {
      Files.delete(path);
    } catch (IOException e) {
      throw new UncheckedIOException("删除失败: " + path.getFileName(), e);
    }
  }

  private static String safe(String name) {
    if (name == null || !SAFE_NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("非法 Agent 名（只允许字母/数字/下划线/连字符）: " + name);
    }
    return name;
  }
}
