package io.oryxos.core.skill;

import io.oryxos.core.context.MarkdownFrontmatter;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.DuplicateKeyException;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.events.CollectionEndEvent;
import org.yaml.snakeyaml.events.CollectionStartEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.NodeEvent;
import org.yaml.snakeyaml.events.ScalarEvent;

/** Reads the bounded, safe L1 metadata view of one managed {@code SKILL.md}. */
public final class SkillMetadataReader {

  private static final Logger LOG = LoggerFactory.getLogger(SkillMetadataReader.class);
  private static final int MAX_DESCRIPTION_CODE_POINTS = 1024;
  private static final int MAX_COMPATIBILITY_CODE_POINTS = 500;
  private static final Set<String> CORE_YAML_TAGS =
      Set.of(
          "tag:yaml.org,2002:null",
          "tag:yaml.org,2002:bool",
          "tag:yaml.org,2002:int",
          "tag:yaml.org,2002:float",
          "tag:yaml.org,2002:binary",
          "tag:yaml.org,2002:timestamp",
          "tag:yaml.org,2002:omap",
          "tag:yaml.org,2002:pairs",
          "tag:yaml.org,2002:set",
          "tag:yaml.org,2002:str",
          "tag:yaml.org,2002:seq",
          "tag:yaml.org,2002:map");

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
    MarkdownFrontmatter.Parsed parsed =
        MarkdownFrontmatter.read(
            entry, limits.maxSkillMarkdownBytes(), limits.maxFrontmatterBytes());
    Map<Object, Object> manifest =
        parseYaml(
            parsed.yaml(),
            limits.maxYamlNestingDepth(),
            toIntLimit(limits.maxFrontmatterBytes()),
            directoryName);
    if (!parsed.hasNonBlankPrompt()) {
      throw failure(SkillValidationCode.EMPTY_PROMPT, "SKILL.md prompt is empty");
    }

    String name = requiredName(manifest.get("name"));
    if (!name.equals(directoryName)) {
      throw failure(
          SkillValidationCode.NAME_DIRECTORY_MISMATCH,
          "Skill name does not match directory " + directoryName);
    }
    String description = requiredDescription(manifest.get("description"));
    String license = optionalString(manifest.get("license"), "license");
    String compatibility = optionalString(manifest.get("compatibility"), "compatibility");
    if (compatibility != null && codePointCount(compatibility) > MAX_COMPATIBILITY_CODE_POINTS) {
      throw failure(SkillValidationCode.INVALID_YAML, "Skill compatibility exceeds 500 characters");
    }
    Map<String, String> metadata = readMetadata(manifest.get("metadata"), name);
    String allowedTools = optionalString(manifest.get("allowed-tools"), "allowed-tools");

    return new SkillMetadata(
        name,
        description,
        license,
        compatibility,
        metadata,
        allowedTools,
        entry,
        "skills/" + name + "/SKILL.md");
  }

  /** Package-private so security tests can pin LoaderOptions independently of byte budgets. */
  Map<Object, Object> parseYaml(
      String yamlText, int maxNestingDepth, int maxCodePoints, String safeSkillName) {
    if (yamlText.codePointCount(0, yamlText.length()) > maxCodePoints) {
      throw failure(
          SkillValidationCode.YAML_CODE_POINTS_EXCEEDED,
          safeSegment(safeSkillName) + " frontmatter exceeds the YAML code-point limit");
    }
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(0);
    options.setAllowRecursiveKeys(false);
    options.setNestingDepthLimit(maxNestingDepth);
    options.setCodePointLimit(maxCodePoints);
    options.setTagInspector(tag -> CORE_YAML_TAGS.contains(tag.getValue()));
    Yaml yaml = new Yaml(new SafeConstructor(options));

    inspectEvents(yaml, yamlText, maxNestingDepth, safeSkillName);
    Object loaded;
    try {
      loaded = yaml.load(yamlText);
    } catch (DuplicateKeyException e) {
      throw failure(
          SkillValidationCode.UNSAFE_YAML,
          safeSegment(safeSkillName) + " frontmatter contains duplicate keys");
    } catch (YAMLException e) {
      SkillValidationCode code =
          isUnsafeYamlFailure(e)
              ? SkillValidationCode.UNSAFE_YAML
              : SkillValidationCode.INVALID_YAML;
      throw failure(code, safeSegment(safeSkillName) + " frontmatter is invalid YAML");
    }
    if (!(loaded instanceof Map<?, ?> rawMap)) {
      throw failure(
          SkillValidationCode.INVALID_YAML,
          safeSegment(safeSkillName) + " frontmatter must be a mapping");
    }
    Map<Object, Object> manifest = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
      if (!(entry.getKey() instanceof String)) {
        throw failure(
            SkillValidationCode.INVALID_YAML,
            safeSegment(safeSkillName) + " frontmatter keys must be strings");
      }
      manifest.put(entry.getKey(), entry.getValue());
    }
    return manifest;
  }

  private static void inspectEvents(
      Yaml yaml, String yamlText, int maxNestingDepth, String safeSkillName) {
    int depth = 0;
    try {
      for (Event event : yaml.parse(new StringReader(yamlText))) {
        if (event instanceof NodeEvent node && node.getAnchor() != null) {
          throw failure(
              SkillValidationCode.UNSAFE_YAML,
              safeSegment(safeSkillName) + " frontmatter must not use anchors or aliases");
        }
        String tag = explicitTag(event);
        if (tag != null && !CORE_YAML_TAGS.contains(tag)) {
          throw failure(
              SkillValidationCode.UNSAFE_YAML,
              safeSegment(safeSkillName) + " frontmatter contains an unsupported tag");
        }
        if (event instanceof CollectionStartEvent) {
          depth++;
          if (depth > maxNestingDepth) {
            throw failure(
                SkillValidationCode.YAML_NESTING_TOO_DEEP,
                safeSegment(safeSkillName) + " frontmatter is nested too deeply");
          }
        } else if (event instanceof CollectionEndEvent) {
          depth--;
        }
      }
    } catch (SkillValidationException e) {
      throw e;
    } catch (YAMLException e) {
      throw failure(
          SkillValidationCode.INVALID_YAML,
          safeSegment(safeSkillName) + " frontmatter is invalid YAML");
    }
  }

  private static String explicitTag(Event event) {
    if (event instanceof ScalarEvent scalar) {
      return scalar.getTag();
    }
    if (event instanceof CollectionStartEvent collection) {
      return collection.getTag();
    }
    return null;
  }

  private static boolean isUnsafeYamlFailure(YAMLException error) {
    String type = error.getClass().getSimpleName();
    return type.contains("Constructor") || type.contains("Duplicate") || type.contains("Tag");
  }

  private static String requiredName(Object value) {
    if (!(value instanceof String name) || !SkillMetadata.isValidName(name)) {
      throw failure(SkillValidationCode.INVALID_NAME, "Skill name is invalid");
    }
    return name;
  }

  private static String requiredDescription(Object value) {
    if (value == null) {
      throw failure(SkillValidationCode.MISSING_DESCRIPTION, "Skill description is required");
    }
    if (!(value instanceof String description)) {
      throw failure(SkillValidationCode.INVALID_YAML, "Skill description must be a string");
    }
    if (description.isBlank()) {
      throw failure(SkillValidationCode.MISSING_DESCRIPTION, "Skill description is required");
    }
    description = description.strip();
    if (codePointCount(description) > MAX_DESCRIPTION_CODE_POINTS) {
      throw failure(
          SkillValidationCode.DESCRIPTION_TOO_LONG, "Skill description exceeds 1024 characters");
    }
    return description;
  }

  private static String optionalString(Object value, String field) {
    if (value == null) {
      return null;
    }
    if (!(value instanceof String text)) {
      throw failure(SkillValidationCode.INVALID_YAML, "Skill " + field + " must be a string");
    }
    return text.isBlank() ? null : text.strip();
  }

  private static Map<String, String> readMetadata(Object value, String skillName) {
    if (value == null) {
      return Map.of();
    }
    if (!(value instanceof Map<?, ?> rawMetadata)) {
      throw failure(SkillValidationCode.INVALID_METADATA, "Skill metadata must be a mapping");
    }
    Map<String, String> metadata = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : rawMetadata.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw failure(SkillValidationCode.INVALID_METADATA, "Skill metadata keys must be strings");
      }
      if ("openclaw".equals(key)
          && entry.getValue() instanceof Map<?, ?> openClaw
          && openClaw.containsKey("requires")) {
        LOG.warn("LEGACY_OPENCLAW_REQUIRES_IGNORED for Skill {}", safeSegment(skillName));
        if (openClaw.size() == 1) {
          continue;
        }
      }
      if (!(entry.getValue() instanceof String text)) {
        throw failure(
            SkillValidationCode.INVALID_METADATA, "Skill metadata values must be strings");
      }
      metadata.put(key, text);
    }
    return Map.copyOf(metadata);
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
