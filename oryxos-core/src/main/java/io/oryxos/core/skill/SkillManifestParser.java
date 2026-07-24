package io.oryxos.core.skill;

import io.oryxos.core.context.MarkdownFrontmatter;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
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
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

/** Single canonical parser shared by import, catalog scanning and re-enable validation. */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "CRLF_INJECTION_LOGS",
    justification =
        "Logged names have already passed the strict single-line ASCII SkillName grammar.")
public final class SkillManifestParser {

  private static final Logger LOG = LoggerFactory.getLogger(SkillManifestParser.class);
  private static final int DEFAULT_MAX_CODE_POINTS = 64 * 1024;
  private static final int DEFAULT_MAX_NESTING_DEPTH = 8;
  private static final int MAX_DESCRIPTION_CODE_POINTS = 1024;
  private static final int MAX_COMPATIBILITY_CODE_POINTS = 500;
  private static final Set<String> CORE_YAML_TAGS =
      Set.of(
          Tag.NULL.getValue(),
          Tag.BOOL.getValue(),
          Tag.INT.getValue(),
          Tag.FLOAT.getValue(),
          Tag.BINARY.getValue(),
          Tag.TIMESTAMP.getValue(),
          Tag.OMAP.getValue(),
          Tag.PAIRS.getValue(),
          Tag.SET.getValue(),
          Tag.STR.getValue(),
          Tag.SEQ.getValue(),
          Tag.MAP.getValue());

  private final int maxCodePoints;
  private final int maxNestingDepth;

  public SkillManifestParser() {
    this(DEFAULT_MAX_CODE_POINTS, DEFAULT_MAX_NESTING_DEPTH);
  }

  public SkillManifestParser(int maxCodePoints, int maxNestingDepth) {
    if (maxCodePoints <= 0 || maxNestingDepth <= 0) {
      throw new IllegalArgumentException("YAML limits must be positive");
    }
    this.maxCodePoints = maxCodePoints;
    this.maxNestingDepth = maxNestingDepth;
  }

  public ParsedSkill parse(String content, boolean validateName) {
    MarkdownFrontmatter.SkillDocument document = MarkdownFrontmatter.parseSkillDocument(content);
    Map<Object, Object> yaml = parseYaml(document.yaml(), "Skill");
    String rawName = requiredString(yaml.get("name"), "name", SkillValidationCode.INVALID_NAME);
    SkillName name;
    try {
      name = SkillName.parse(rawName);
    } catch (SkillValidationException error) {
      if (validateName) {
        throw error;
      }
      throw error;
    }
    String description = requiredDescription(yaml.get("description"));
    SkillVersion version = parseVersion(yaml.get("version"));
    String license = optionalString(yaml.get("license"), "license");
    String compatibility = optionalString(yaml.get("compatibility"), "compatibility");
    if (compatibility != null
        && compatibility.codePointCount(0, compatibility.length())
            > MAX_COMPATIBILITY_CODE_POINTS) {
      throw failure(SkillValidationCode.INVALID_YAML, "Skill compatibility exceeds 500 characters");
    }
    boolean legacy = hasLegacyRequires(yaml.get("metadata"));
    Map<String, String> metadata = parseMetadata(yaml.get("metadata"));
    String allowedTools = optionalString(yaml.get("allowed-tools"), "allowed-tools");
    ActivationCriteria rawActivation = parseActivation(yaml.get("activation"));
    GatingRequirements rawRequires = parseRequires(yaml.get("requires"));
    ActivationCriteria activation = rawActivation.enforceLimits();
    GatingRequirements requires = rawRequires.enforceLimits();

    if (legacy) {
      LOG.warn(
          "event=skill.manifest.legacy skill={} reasonCode=LEGACY_OPENCLAW_REQUIRES", name.value());
    }
    if (!activation.equals(rawActivation) || !requires.equals(rawRequires)) {
      LOG.warn(
          "event=skill.manifest.limits skill={} reasonCode=MANIFEST_LIMITS_APPLIED", name.value());
    }

    SkillManifest manifest =
        new SkillManifest(
            name,
            description,
            version,
            license,
            compatibility,
            metadata,
            allowedTools,
            activation,
            requires);
    return new ParsedSkill(manifest, document.promptContent());
  }

  Map<Object, Object> parseYaml(String yamlText, String safeSkillName) {
    if (yamlText.codePointCount(0, yamlText.length()) > maxCodePoints) {
      throw failure(
          SkillValidationCode.YAML_CODE_POINTS_EXCEEDED,
          safeName(safeSkillName) + " frontmatter exceeds the YAML code-point limit");
    }
    LoaderOptions loaderOptions = new LoaderOptions();
    loaderOptions.setAllowDuplicateKeys(false);
    loaderOptions.setMaxAliasesForCollections(0);
    loaderOptions.setAllowRecursiveKeys(false);
    loaderOptions.setNestingDepthLimit(maxNestingDepth);
    loaderOptions.setCodePointLimit(maxCodePoints);
    loaderOptions.setTagInspector(tag -> CORE_YAML_TAGS.contains(tag.getValue()));
    DumperOptions dumperOptions = new DumperOptions();
    Yaml yaml =
        new Yaml(
            new SafeConstructor(loaderOptions),
            new Representer(dumperOptions),
            dumperOptions,
            loaderOptions,
            new Yaml12Resolver());
    inspectEvents(yaml, yamlText, safeSkillName);
    Object loaded;
    try {
      loaded = yaml.load(yamlText);
    } catch (DuplicateKeyException error) {
      throw failure(
          SkillValidationCode.UNSAFE_YAML,
          safeName(safeSkillName) + " frontmatter contains duplicate keys");
    } catch (YAMLException error) {
      throw failure(
          isUnsafeYamlFailure(error)
              ? SkillValidationCode.UNSAFE_YAML
              : SkillValidationCode.INVALID_YAML,
          safeName(safeSkillName) + " frontmatter is invalid YAML");
    }
    if (!(loaded instanceof Map<?, ?> rawMap)) {
      throw failure(
          SkillValidationCode.INVALID_YAML,
          safeName(safeSkillName) + " frontmatter must be a mapping");
    }
    Map<Object, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
      if (!(entry.getKey() instanceof String)) {
        throw failure(
            SkillValidationCode.INVALID_YAML,
            safeName(safeSkillName) + " frontmatter keys must be strings");
      }
      result.put(entry.getKey(), entry.getValue());
    }
    return result;
  }

  private void inspectEvents(Yaml yaml, String yamlText, String safeSkillName) {
    int depth = 0;
    try {
      for (Event event : yaml.parse(new StringReader(yamlText))) {
        if (event instanceof NodeEvent node && node.getAnchor() != null) {
          throw failure(
              SkillValidationCode.UNSAFE_YAML,
              safeName(safeSkillName) + " frontmatter must not use anchors or aliases");
        }
        String tag = explicitTag(event);
        if (tag != null && !CORE_YAML_TAGS.contains(tag)) {
          throw failure(
              SkillValidationCode.UNSAFE_YAML,
              safeName(safeSkillName) + " frontmatter contains an unsupported tag");
        }
        if (event instanceof CollectionStartEvent) {
          depth++;
          if (depth > maxNestingDepth) {
            throw failure(
                SkillValidationCode.YAML_NESTING_TOO_DEEP,
                safeName(safeSkillName) + " frontmatter is nested too deeply");
          }
        } else if (event instanceof CollectionEndEvent) {
          depth--;
        }
      }
    } catch (SkillValidationException error) {
      throw error;
    } catch (YAMLException error) {
      throw failure(
          SkillValidationCode.INVALID_YAML,
          safeName(safeSkillName) + " frontmatter is invalid YAML");
    }
  }

  private static SkillVersion parseVersion(Object value) {
    if (value == null) {
      return null;
    }
    if (!(value instanceof String text)) {
      throw failure(SkillValidationCode.INVALID_VERSION, "Skill version is invalid");
    }
    return SkillVersion.parse(text);
  }

  private static String requiredDescription(Object value) {
    if (value == null) {
      throw failure(SkillValidationCode.MISSING_DESCRIPTION, "Skill description is required");
    }
    if (!(value instanceof String description)) {
      throw failure(SkillValidationCode.INVALID_YAML, "Skill description must be a string");
    }
    description = description.strip();
    if (description.isEmpty()) {
      throw failure(SkillValidationCode.MISSING_DESCRIPTION, "Skill description is required");
    }
    if (description.codePointCount(0, description.length()) > MAX_DESCRIPTION_CODE_POINTS) {
      throw failure(
          SkillValidationCode.DESCRIPTION_TOO_LONG, "Skill description exceeds 1024 characters");
    }
    return description;
  }

  private static Map<String, String> parseMetadata(Object value) {
    if (value == null) {
      return Map.of();
    }
    Map<?, ?> map = mapping(value, "metadata");
    Map<String, String> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw failure(SkillValidationCode.INVALID_METADATA, "Skill metadata keys must be strings");
      }
      if ("openclaw".equals(key) && entry.getValue() instanceof Map<?, ?>) {
        continue;
      }
      if (!(entry.getValue() instanceof String text)) {
        throw failure(
            SkillValidationCode.INVALID_METADATA, "Skill metadata values must be strings");
      }
      result.put(key, text);
    }
    return Map.copyOf(result);
  }

  private static ActivationCriteria parseActivation(Object value) {
    if (value == null) {
      return ActivationCriteria.empty();
    }
    Map<?, ?> map = mapping(value, "activation");
    return new ActivationCriteria(
        stringList(map.get("keywords"), "activation.keywords"),
        stringList(map.get("exclude_keywords"), "activation.exclude_keywords"),
        stringList(map.get("patterns"), "activation.patterns"),
        stringList(map.get("tags"), "activation.tags"),
        optionalInteger(map.get("max_context_tokens"), "activation.max_context_tokens"),
        optionalString(map.get("setup_marker"), "activation.setup_marker"));
  }

  private static GatingRequirements parseRequires(Object value) {
    if (value == null) {
      return GatingRequirements.empty();
    }
    Map<?, ?> map = mapping(value, "requires");
    return new GatingRequirements(
        stringList(map.get("bins"), "requires.bins"),
        stringList(map.get("env"), "requires.env"),
        stringList(map.get("config"), "requires.config"),
        stringList(map.get("skills"), "requires.skills"));
  }

  private static boolean hasLegacyRequires(Object value) {
    if (!(value instanceof Map<?, ?> metadata)) {
      return false;
    }
    Object openclaw = metadata.get("openclaw");
    return openclaw instanceof Map<?, ?> nested && nested.containsKey("requires");
  }

  private static Map<?, ?> mapping(Object value, String field) {
    if (!(value instanceof Map<?, ?> map)) {
      throw failure(SkillValidationCode.INVALID_YAML, "Skill " + field + " must be a mapping");
    }
    return map;
  }

  private static List<String> stringList(Object value, String field) {
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> list)) {
      throw failure(SkillValidationCode.INVALID_YAML, "Skill " + field + " must be a list");
    }
    List<String> result = new ArrayList<>(list.size());
    for (Object item : list) {
      if (!(item instanceof String text)) {
        throw failure(
            SkillValidationCode.INVALID_YAML, "Skill " + field + " values must be strings");
      }
      result.add(text);
    }
    return List.copyOf(result);
  }

  private static Integer optionalInteger(Object value, String field) {
    if (value == null) {
      return null;
    }
    if (!(value instanceof Number number)) {
      throw failure(SkillValidationCode.INVALID_YAML, "Skill " + field + " must be an integer");
    }
    return number.intValue();
  }

  private static String requiredString(
      Object value, String field, SkillValidationCode missingOrInvalidCode) {
    if (!(value instanceof String text) || text.isEmpty()) {
      throw failure(missingOrInvalidCode, "Skill " + field + " is invalid");
    }
    return text;
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

  private static String safeName(String value) {
    if (value == null || value.isBlank()) {
      return "Skill";
    }
    return value.replaceAll("[^a-zA-Z0-9._-]", "_").substring(0, Math.min(64, value.length()));
  }

  private static SkillValidationException failure(SkillValidationCode code, String safeMessage) {
    return new SkillValidationException(code, safeMessage);
  }

  /** SnakeYAML defaults to YAML 1.1 booleans; this resolver limits them to YAML 1.2 values. */
  private static final class Yaml12Resolver extends Resolver {

    private static final Pattern YAML12_BOOLEAN =
        Pattern.compile("^(?:true|True|TRUE|false|False|FALSE)$");

    @Override
    protected void addImplicitResolvers() {
      addImplicitResolver(Tag.BOOL, YAML12_BOOLEAN, "tTfF");
      addImplicitResolver(Tag.INT, Resolver.INT, "-+0123456789");
      addImplicitResolver(Tag.FLOAT, Resolver.FLOAT, "-+0123456789.");
      addImplicitResolver(Tag.MERGE, Resolver.MERGE, "<");
      addImplicitResolver(Tag.NULL, Resolver.NULL, "~nN\0");
      addImplicitResolver(Tag.NULL, Resolver.EMPTY, null);
      addImplicitResolver(Tag.TIMESTAMP, Resolver.TIMESTAMP, "0123456789");
      addImplicitResolver(Tag.YAML, Resolver.YAML, "!&*");
    }
  }
}
