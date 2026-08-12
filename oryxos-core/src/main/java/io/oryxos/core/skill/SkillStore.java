package io.oryxos.core.skill;

import io.oryxos.core.fs.RealPathBoundary;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 全局 Skill 库的文件读写，限定在 {@code .oryxos/skills/} 内（第 32 节）。
 *
 * <p>write：把一段 {@code SKILL.md} 写进 {@code .oryxos/skills/<name>/}；archive：把完整目录移动到 {@code
 * .oryxos/archive/skills/}。name 必须是安全目录段（只允许字母/数字/下划线/连字符，防路径穿越）。
 */
public class SkillStore {

  private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_-]+");
  private static final String SKILL_FILE = "SKILL.md";

  private static final DateTimeFormatter ARCHIVE_TIME =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

  private final Path root;
  private final Path skillsDir;
  private final Path archiveDir;
  private final Clock clock;

  public SkillStore(Path oryxosRoot) {
    this(oryxosRoot, Clock.systemUTC());
  }

  SkillStore(Path oryxosRoot, Clock clock) {
    this.root = oryxosRoot.toAbsolutePath().normalize();
    this.skillsDir = root.resolve("skills");
    this.archiveDir = root.resolve("archive");
    this.clock = clock;
  }

  /** 写 {@code .oryxos/skills/<name>/SKILL.md}，返回该 Skill 目录。 */
  public Path write(String name, String skillMarkdown) {
    Path dir = skillsDir.resolve(safe(name));
    requireSafe(dir.resolve(SKILL_FILE));
    try {
      Files.createDirectories(dir);
      Files.writeString(dir.resolve(SKILL_FILE), skillMarkdown);
    } catch (IOException e) {
      throw new UncheckedIOException("写入 Skill 目录失败: " + name, e);
    }
    return dir;
  }

  /**
   * 整目录导入：{@code files} 的键是相对 Skill 目录的路径（如 {@code SKILL.md}、{@code scripts/foo.py}），值是文件内容。 每个路径
   * normalize 后必须落在该 Skill 目录内（防穿越）。配合"从 GitHub 目录导入"（第 32 节）：拉下来的整个文件夹原样落盘， 不止一份 SKILL.md。
   */
  public Path writeAll(String name, Map<String, String> files) {
    Path dir = skillsDir.resolve(safe(name)).normalize();
    requireSafe(dir);
    try {
      Files.createDirectories(dir);
      for (Map.Entry<String, String> entry : files.entrySet()) {
        Path target = dir.resolve(entry.getKey()).normalize();
        if (!target.startsWith(dir)) {
          throw new IllegalArgumentException("非法文件路径: " + entry.getKey());
        }
        requireSafe(target);
        Path parent = target.getParent();
        if (parent != null) {
          Files.createDirectories(parent);
        }
        Files.writeString(target, entry.getValue());
      }
    } catch (IOException e) {
      throw new UncheckedIOException("写入 Skill 目录失败: " + name, e);
    }
    return dir;
  }

  public boolean exists(String name) {
    Path file = skillsDir.resolve(safe(name)).resolve(SKILL_FILE);
    return RealPathBoundary.isWithin(skillsDir, file) && Files.isRegularFile(file);
  }

  /** Returns whether any filesystem entry already occupies this Skill name, including residue. */
  boolean entryExists(String name) {
    Path directory = skillsDir.resolve(safe(name));
    return Files.exists(directory, LinkOption.NOFOLLOW_LINKS);
  }

  /**
   * Moves the complete installed Skill directory into archive/skills without overwriting history.
   */
  public SkillArchive archive(String name) {
    String safeName = safe(name);
    Path source = skillsDir.resolve(safeName);
    requireSafe(source);
    if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("Skill 不存在: " + safeName);
    }
    Instant archivedAt = clock.instant();
    try {
      prepareSkillArchiveNamespace();
      Path target = uniqueArchivePath(safeName, archivedAt);
      RealPathBoundary.requireWithin(root, target);
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
      return new SkillArchive(safeName, root.relativize(target), archivedAt);
    } catch (IOException e) {
      throw new UncheckedIOException("归档 Skill 目录失败: " + safeName, e);
    }
  }

  /** 返回受安全 name 约束的公共 Skill 目录，供加载器在整目录导入后做一致性复验。 */
  Path directory(String name) {
    return skillsDir.resolve(safe(name));
  }

  /** Removes a just-created import directory after validation or registration failed. */
  void rollbackCreate(String name) {
    Path directory = skillsDir.resolve(safe(name));
    requireSafe(directory);
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(directory)) {
      paths.sorted(Comparator.reverseOrder()).forEach(SkillStore::deleteRollbackPath);
    } catch (IOException e) {
      throw new UncheckedIOException("回滚 Skill 导入目录失败: " + name, e);
    }
  }

  private void prepareSkillArchiveNamespace() throws IOException {
    Path skillArchives = archiveDir.resolve("skills");
    if (Files.isDirectory(skillArchives, LinkOption.NOFOLLOW_LINKS)
        && Files.isRegularFile(skillArchives.resolve("AGENT.md"))) {
      Path migrated = archiveDir.resolve("skills-" + System.currentTimeMillis());
      while (Files.exists(migrated, LinkOption.NOFOLLOW_LINKS)) {
        migrated = archiveDir.resolve("skills-" + System.nanoTime());
      }
      Files.move(skillArchives, migrated, StandardCopyOption.ATOMIC_MOVE);
    }
    Files.createDirectories(skillArchives);
  }

  private Path uniqueArchivePath(String name, Instant instant) {
    Path skillArchives = archiveDir.resolve("skills");
    String base = name + "-" + ARCHIVE_TIME.format(instant);
    Path candidate = skillArchives.resolve(base);
    int suffix = 2;
    while (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
      candidate = skillArchives.resolve(base + "-" + suffix++);
    }
    return candidate;
  }

  private void requireSafe(Path path) {
    RealPathBoundary.requireWithin(skillsDir, path);
  }

  private static void deleteRollbackPath(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      throw new UncheckedIOException("删除 Skill 导入残留失败: " + path.getFileName(), e);
    }
  }

  private static String safe(String name) {
    if (name == null || !SAFE_NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("非法 Skill 名（只允许字母/数字/下划线/连字符）: " + name);
    }
    return name;
  }
}
