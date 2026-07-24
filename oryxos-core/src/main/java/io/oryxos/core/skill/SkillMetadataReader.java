package io.oryxos.core.skill;

import io.oryxos.core.context.MarkdownFrontmatter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;

/** Reads the bounded, safe L1 metadata view of one managed {@code SKILL.md}. */
public final class SkillMetadataReader {

  public SkillMetadata read(Path agentDir, Path skillDir, SkillLimits limits) {
    if (agentDir == null || skillDir == null || limits == null) {
      throw new IllegalArgumentException("agentDir, skillDir and limits are required");
    }
    Path absoluteAgent = agentDir.toAbsolutePath().normalize();
    Path absoluteSkill = skillDir.toAbsolutePath().normalize();
    String directoryName = safeSegment(skillDir.getFileName());
    if (Files.isSymbolicLink(skillDir)) {
      throw failure(SkillValidationCode.LINK_NOT_ALLOWED, directoryName + " must not be a link");
    }
    if (!Files.isDirectory(skillDir, LinkOption.NOFOLLOW_LINKS)) {
      throw failure(SkillValidationCode.CONTENT_UNREADABLE, directoryName + " must be a directory");
    }
    Path expectedParent = absoluteAgent.resolve("skills");
    if (!expectedParent.equals(absoluteSkill.getParent())) {
      throw failure(
          SkillValidationCode.OUTSIDE_SKILL_ROOT,
          directoryName + " is not a direct child of the Agent skills directory");
    }

    Path entry = absoluteSkill.resolve("SKILL.md");
    MarkdownFrontmatter.read(entry, limits.maxSkillMarkdownBytes(), limits.maxFrontmatterBytes());
    SkillManifest manifest =
        new SkillManifestParser(
                toIntLimit(limits.maxFrontmatterBytes()), limits.maxYamlNestingDepth())
            .parse(readUtf8(entry), true)
            .manifest();
    String name = manifest.name().value();
    if (!name.equals(directoryName)) {
      throw failure(
          SkillValidationCode.NAME_DIRECTORY_MISMATCH,
          "Skill name does not match directory " + directoryName);
    }
    return new SkillMetadata(
        name,
        manifest.description(),
        manifest.license(),
        manifest.compatibility(),
        manifest.metadata(),
        manifest.allowedTools(),
        manifest.version(),
        manifest.activation(),
        manifest.requires(),
        entry,
        "skills/" + name + "/SKILL.md");
  }

  /** Package-private so security tests can pin LoaderOptions independently of byte budgets. */
  Map<Object, Object> parseYaml(
      String yamlText, int maxNestingDepth, int maxCodePoints, String safeSkillName) {
    return new SkillManifestParser(maxCodePoints, maxNestingDepth)
        .parseYaml(yamlText, safeSkillName);
  }

  private static String readUtf8(Path entry) {
    try {
      byte[] bytes = Files.readAllBytes(entry);
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException error) {
      throw failure(SkillValidationCode.INVALID_UTF8, "SKILL.md is not valid UTF-8");
    } catch (IOException error) {
      throw failure(SkillValidationCode.CONTENT_UNREADABLE, "SKILL.md cannot be read");
    }
  }

  private static int codePointCount(String value) {
    return value.codePointCount(0, value.length());
  }

  private static int toIntLimit(long value) {
    return (int) Math.min(Integer.MAX_VALUE, value);
  }

  private static String safeSegment(Path path) {
    return safeSegment(path == null ? null : path.toString());
  }

  private static String safeSegment(String value) {
    if (value == null || value.isBlank()) {
      return "Skill";
    }
    String sanitized = sanitizeControls(value).replace('/', '_').replace('\\', '_');
    return truncateCodePoints(sanitized, 128);
  }

  private static String sanitizeControls(String value) {
    StringBuilder sanitized = new StringBuilder(value.length());
    value
        .codePoints()
        .forEach(
            codePoint ->
                sanitized.appendCodePoint(isUnsafeTextCodePoint(codePoint) ? '_' : codePoint));
    return sanitized.toString();
  }

  private static boolean isUnsafeTextCodePoint(int codePoint) {
    int type = Character.getType(codePoint);
    return Character.isISOControl(codePoint)
        || type == Character.FORMAT
        || type == Character.LINE_SEPARATOR
        || type == Character.PARAGRAPH_SEPARATOR;
  }

  private static String truncateCodePoints(String value, int maxCodePoints) {
    if (codePointCount(value) <= maxCodePoints) {
      return value;
    }
    return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
  }

  private static SkillValidationException failure(SkillValidationCode code, String safeMessage) {
    return new SkillValidationException(code, safeMessage);
  }
}
