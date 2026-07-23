package io.oryxos.core.skill;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 全局 Skill 库的文件读写，限定在 {@code .oryxos/skills/} 内（第 32 节）。
 *
 * <p>write：把一段 {@code SKILL.md} 写进 {@code .oryxos/skills/<name>/}；delete：递归删整个 Skill 目录。 name
 * 必须是安全目录段（只允许字母/数字/下划线/连字符，防路径穿越）。与 {@code AgentStore} 同构，但 Skill 是纯共享资产、删除即物理删（无 archive）。
 */
public class SkillStore {

  private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_-]+");
  private static final String SKILL_FILE = "SKILL.md";

  private final Path skillsDir;

  public SkillStore(Path oryxosRoot) {
    this.skillsDir = oryxosRoot.resolve("skills");
  }

  /** 写 {@code .oryxos/skills/<name>/SKILL.md}，返回该 Skill 目录。 */
  public Path write(String name, String skillMarkdown) {
    Path dir = skillsDir.resolve(safe(name));
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
      throw new UncheckedIOException("写入 Skill 目录失败: " + name, e);
    }
    return dir;
  }

  /** 递归删除一个 Skill 目录。 */
  public void delete(String name) {
    Path dir = skillsDir.resolve(safe(name));
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder()).forEach(SkillStore::deleteOne);
    } catch (IOException e) {
      throw new UncheckedIOException("删除 Skill 目录失败: " + name, e);
    }
  }

  public boolean exists(String name) {
    return Files.isRegularFile(skillsDir.resolve(safe(name)).resolve(SKILL_FILE));
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
      throw new IllegalArgumentException("非法 Skill 名（只允许字母/数字/下划线/连字符）: " + name);
    }
    return name;
  }
}
