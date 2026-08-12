package io.oryxos.core.agent;

import io.oryxos.core.fs.RealPathBoundary;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Agent 目录的文件读写，限定在 {@code .oryxos/} 内（第 30 节）。
 *
 * <p>write：把一段 {@code AGENT.md} 写进 {@code .oryxos/agents/<name>/}；delete：回滚删已写目录； archive：把整个 Agent
 * 目录移进 {@code .oryxos/archive/}（删除不物理删，定义可追溯）。 name 必须是安全目录段（防路径穿越）。
 */
public class AgentStore {

  private static final String SKILLS_NAMESPACE = "skills";

  private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_-]+");
  private static final String AGENT_FILE = "AGENT.md";

  private final Path agentsDir;
  private final Path archiveDir;
  private final Clock clock;

  public AgentStore(Path oryxosRoot) {
    this(oryxosRoot, Clock.systemUTC());
  }

  AgentStore(Path oryxosRoot, Clock clock) {
    Path root = oryxosRoot.toAbsolutePath().normalize();
    this.agentsDir = root.resolve("agents");
    this.archiveDir = root.resolve("archive");
    this.clock = clock;
  }

  /**
   * 读 .oryxos/agents/&lt;name&gt;/AGENT.md 的原始文本；缺文件抛 {@link IllegalStateException}（调用方应先确认 Agent
   * 存在）。
   */
  public String read(String name) {
    Path file = agentsDir.resolve(safe(name)).resolve(AGENT_FILE);
    if (!Files.isRegularFile(file)) {
      throw new IllegalStateException("Agent 目录缺少 AGENT.md: " + name);
    }
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException("读取 Agent 目录失败: " + name, e);
    }
  }

  /** 写 .oryxos/agents/&lt;name&gt;/AGENT.md，返回该 Agent 目录。 */
  public Path write(String name, String agentMarkdown) {
    return writeAll(name, Map.of(AGENT_FILE, agentMarkdown));
  }

  /**
   * 脚手架式写入整个 Agent 目录：{@code files} 的键是相对 Agent 目录的路径（如 {@code AGENT.md}、{@code
   * scripts/example.py}），值是文件内容。每个路径 normalize 后必须落在该 Agent 目录内（防穿越）。返回该 Agent 目录。
   */
  public synchronized Path writeAll(String name, Map<String, String> files) {
    Path dir = agentsDir.resolve(safe(name)).normalize();
    requireSafe(dir);
    boolean existed = Files.exists(dir, LinkOption.NOFOLLOW_LINKS);
    List<StagedWrite> staged = new ArrayList<>();
    try {
      Files.createDirectories(dir);
      Set<Path> uniqueTargets = new HashSet<>();
      for (Map.Entry<String, String> entry : files.entrySet()) {
        Path target = writableTarget(dir, entry.getKey());
        if (!uniqueTargets.add(target)) {
          throw new IllegalArgumentException("重复文件路径: " + entry.getKey());
        }
        Path parent = target.getParent();
        if (parent != null) {
          Files.createDirectories(parent);
        }
        Path temporary =
            target.resolveSibling("." + target.getFileName() + ".write-" + UUID.randomUUID());
        Files.writeString(temporary, entry.getValue());
        Path backup =
            target.resolveSibling("." + target.getFileName() + ".backup-" + UUID.randomUUID());
        staged.add(new StagedWrite(target, temporary, backup));
      }
      for (StagedWrite write : staged) {
        if (Files.exists(write.target, LinkOption.NOFOLLOW_LINKS)) {
          moveAtomic(write.target, write.backup);
          write.originalMoved = true;
        }
        moveAtomic(write.temporary, write.target);
        write.committed = true;
      }
      for (StagedWrite write : staged) {
        try {
          Files.deleteIfExists(write.backup);
        } catch (IOException ignored) {
          // 提交已经完成；遗留隐藏备份比回滚已成功提交的用户文件更安全。
        }
      }
    } catch (IOException e) {
      rollback(staged);
      if (!existed) {
        delete(dir);
      }
      throw new UncheckedIOException("写入 Agent 目录失败: " + name, e);
    } catch (RuntimeException e) {
      rollback(staged);
      if (!existed) {
        delete(dir);
      }
      throw e;
    }
    return dir;
  }

  FileSnapshot snapshot(String name, Set<String> relativePaths) {
    Path dir = agentsDir.resolve(safe(name)).normalize();
    Map<String, byte[]> existing = new LinkedHashMap<>();
    Set<String> absent = new HashSet<>();
    try {
      for (String relativePath : relativePaths) {
        Path target = writableTarget(dir, relativePath);
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
          existing.put(relativePath, Files.readAllBytes(target));
        } else {
          absent.add(relativePath);
        }
      }
      return new FileSnapshot(name, existing, absent);
    } catch (IOException e) {
      throw new UncheckedIOException("读取 Agent 文件快照失败: " + name, e);
    }
  }

  void restore(FileSnapshot snapshot) {
    Path dir = agentsDir.resolve(safe(snapshot.agentName)).normalize();
    try {
      for (String relativePath : snapshot.absent) {
        Files.deleteIfExists(writableTarget(dir, relativePath));
      }
      for (Map.Entry<String, byte[]> entry : snapshot.existing.entrySet()) {
        Path target = writableTarget(dir, entry.getKey());
        Path parent = target.getParent();
        if (parent == null) {
          throw new IllegalArgumentException("Agent 文件缺少父目录: " + entry.getKey());
        }
        Files.createDirectories(parent);
        Files.write(target, entry.getValue());
      }
    } catch (IOException e) {
      throw new UncheckedIOException("恢复 Agent 文件快照失败: " + snapshot.agentName, e);
    }
  }

  /** 递归删除一个 Agent 目录（create 中途失败回滚用）。 */
  public void delete(Path agentDir) {
    requireSafe(agentDir);
    if (!Files.exists(agentDir, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(agentDir)) {
      walk.sorted(Comparator.reverseOrder()).forEach(AgentStore::deleteOne);
    } catch (IOException e) {
      throw new UncheckedIOException("删除 Agent 目录失败: " + agentDir.getFileName(), e);
    }
  }

  /** 整个 Agent 目录移入 .oryxos/archive/（不物理删）；目标已存在则加时间戳后缀避免覆盖。 */
  public synchronized void archive(String name) {
    Path src = agentsDir.resolve(safe(name));
    requireSafe(src);
    try {
      Files.createDirectories(archiveDir);
      Path dst = uniqueArchivePath(name);
      RealPathBoundary.requireWithin(archiveDir, dst);
      Files.move(src, dst);
    } catch (IOException e) {
      throw new UncheckedIOException("归档 Agent 目录失败: " + name, e);
    }
  }

  private Path uniqueArchivePath(String name) {
    Path direct = archiveDir.resolve(name);
    if (!SKILLS_NAMESPACE.equals(name) && !Files.exists(direct, LinkOption.NOFOLLOW_LINKS)) {
      return direct;
    }
    String base = name + "-" + clock.millis();
    Path candidate = archiveDir.resolve(base);
    int suffix = 2;
    while (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
      candidate = archiveDir.resolve(base + "-" + suffix++);
    }
    return candidate;
  }

  private static void deleteOne(Path path) {
    try {
      Files.delete(path);
    } catch (IOException e) {
      throw new UncheckedIOException("删除失败: " + path.getFileName(), e);
    }
  }

  private Path writableTarget(Path dir, String relativePath) {
    Path target = dir.resolve(relativePath).normalize();
    if (!target.startsWith(dir)) {
      throw new IllegalArgumentException("非法文件路径: " + relativePath);
    }
    Path relative = dir.relativize(target);
    if (relative.getNameCount() > 0 && SKILLS_NAMESPACE.equals(relative.getName(0).toString())) {
      throw new IllegalArgumentException("skills/ 是 Agent Skill 绑定保留目录，禁止写普通文件");
    }
    requireSafe(target);
    if (isNonRegularTarget(target)) {
      throw new IllegalArgumentException("Agent 文件目标不是普通文件: " + relativePath);
    }
    return target;
  }

  private static boolean isNonRegularTarget(Path target) {
    if (Files.isSymbolicLink(target)) {
      return true;
    }
    return Files.exists(target, LinkOption.NOFOLLOW_LINKS)
        && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS);
  }

  private static void moveAtomic(Path source, Path target) throws IOException {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      throw new IOException("文件系统不支持原子移动: " + source, e);
    }
  }

  private static void rollback(List<StagedWrite> staged) {
    for (int i = staged.size() - 1; i >= 0; i--) {
      StagedWrite write = staged.get(i);
      try {
        if (write.committed) {
          Files.deleteIfExists(write.target);
        }
        if (write.originalMoved && Files.exists(write.backup, LinkOption.NOFOLLOW_LINKS)) {
          moveAtomic(write.backup, write.target);
        }
        Files.deleteIfExists(write.temporary);
      } catch (IOException ignored) {
        // 原始异常优先；一致性检查和隐藏备份保留恢复线索。
      }
    }
  }

  static final class FileSnapshot {
    private final String agentName;
    private final Map<String, byte[]> existing;
    private final Set<String> absent;

    private FileSnapshot(String agentName, Map<String, byte[]> existing, Set<String> absent) {
      this.agentName = agentName;
      this.existing = Map.copyOf(existing);
      this.absent = Set.copyOf(absent);
    }
  }

  private static final class StagedWrite {
    private final Path target;
    private final Path temporary;
    private final Path backup;
    private boolean originalMoved;
    private boolean committed;

    private StagedWrite(Path target, Path temporary, Path backup) {
      this.target = target;
      this.temporary = temporary;
      this.backup = backup;
    }
  }

  private static String safe(String name) {
    if (name == null || !SAFE_NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("非法 Agent 名（只允许字母/数字/下划线/连字符）: " + name);
    }
    return name;
  }

  private void requireSafe(Path path) {
    RealPathBoundary.requireWithin(agentsDir, path);
  }
}
