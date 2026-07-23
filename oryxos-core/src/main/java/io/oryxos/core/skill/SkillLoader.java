package io.oryxos.core.skill;

import io.oryxos.core.agent.AgentMarkdown;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 启动时扫描 {@code .oryxos/skills/} 下每个子目录的 {@code SKILL.md}，派生成 {@link Skill} 建立索引（第 32 节）。
 *
 * <p>与 {@code AgentLoader} 同构：单个坏目录记 ERROR 跳过、不阻断其余加载。frontmatter 用 {@link AgentMarkdown#split}
 * 拆分（复用 同一套 frontmatter/正文解析），name 缺省取目录名。
 */
public class SkillLoader {

  private static final Logger LOG = LoggerFactory.getLogger(SkillLoader.class);
  private static final String SKILL_FILE = "SKILL.md";

  private final Path skillsDir;

  public SkillLoader(Path skillsDir) {
    this.skillsDir = skillsDir;
  }

  /** 扫描目录并返回加载成功的 Skill 索引；单目录失败只记日志。 */
  public SkillRegistry loadAll() {
    Map<String, Skill> loaded = new LinkedHashMap<>();
    if (!Files.isDirectory(skillsDir)) {
      LOG.warn("Skill 目录不存在，跳过加载: {}", sanitize(skillsDir.toString()));
      return new SkillRegistry(loaded);
    }
    try (Stream<Path> dirs = Files.list(skillsDir)) {
      dirs.filter(Files::isDirectory)
          .sorted()
          .forEach(
              dir -> {
                try {
                  Skill skill = deriveSkill(dir);
                  loaded.put(skill.name(), skill);
                } catch (RuntimeException e) {
                  LOG.error(
                      "跳过损坏的 Skill 目录 {}: {}",
                      sanitize(String.valueOf(dir.getFileName())),
                      sanitize(e.getMessage()));
                }
              });
    } catch (IOException e) {
      LOG.error("扫描 Skill 目录失败: {}", sanitize(e.getMessage()));
    }
    return new SkillRegistry(loaded);
  }

  /** 读 {@code <dir>/SKILL.md} 派生 Skill；缺文件抛异常。name 取 frontmatter，缺省用目录名。 */
  public Skill deriveSkill(Path skillDir) {
    Path skillMd = skillDir.resolve(SKILL_FILE);
    if (!Files.isRegularFile(skillMd)) {
      throw new IllegalArgumentException("Skill 目录缺少 SKILL.md: " + skillDir.getFileName());
    }
    String dirName = String.valueOf(skillDir.getFileName());
    return parse(read(skillMd), dirName);
  }

  /** 把一份 SKILL.md 文本解析成 Skill（name 缺省用 fallbackName）。 */
  public Skill parse(String markdown, String fallbackName) {
    AgentMarkdown.Parsed parsed = AgentMarkdown.split(markdown);
    Object nameVal = parsed.frontmatter().get("name");
    String name =
        nameVal == null || String.valueOf(nameVal).isBlank()
            ? fallbackName
            : String.valueOf(nameVal);
    Object descVal = parsed.frontmatter().get("description");
    String description = descVal == null ? "" : String.valueOf(descVal);
    return new Skill(name, description, parsed.body());
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException("读取 SKILL.md 失败: " + file.getFileName(), e);
    }
  }

  /** 日志参数消毒：去掉换行，防日志伪造（CRLF injection）。 */
  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
