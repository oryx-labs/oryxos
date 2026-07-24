package io.oryxos.core.skill;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Immutable L1 metadata derived from a valid {@code SKILL.md} frontmatter. */
public record SkillMetadata(
    String name,
    String description,
    String license,
    String compatibility,
    Map<String, String> metadata,
    String allowedTools,
    SkillVersion version,
    ActivationCriteria activation,
    GatingRequirements requires,
    Path entryPath,
    String relativeEntry) {

  private static final int MAX_NAME_CHARS = 64;
  private static final int MAX_DESCRIPTION_CHARS = 1024;
  private static final int MAX_COMPATIBILITY_CHARS = 500;
  private static final String CURRENT_DIRECTORY = ".";
  private static final String PARENT_DIRECTORY = "..";
  private static final char BACKSLASH = '\\';
  private static final char LOWERCASE_ASCII_START = 'a';
  private static final char LOWERCASE_ASCII_END = 'z';
  private static final char DIGIT_ASCII_START = '0';
  private static final char DIGIT_ASCII_END = '9';

  public SkillMetadata {
    if (!isValidName(name)) {
      throw new IllegalArgumentException("invalid Skill name: " + name);
    }
    if (description == null || description.isBlank()) {
      throw new IllegalArgumentException("Skill description must not be blank");
    }
    description = description.strip();
    if (codePointCount(description) > MAX_DESCRIPTION_CHARS) {
      throw new IllegalArgumentException("Skill description exceeds 1024 characters");
    }
    license = normalizeOptional(license);
    compatibility = normalizeOptional(compatibility);
    if (compatibility != null && codePointCount(compatibility) > MAX_COMPATIBILITY_CHARS) {
      throw new IllegalArgumentException("Skill compatibility exceeds 500 characters");
    }
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    allowedTools = normalizeOptional(allowedTools);
    activation = activation == null ? ActivationCriteria.empty() : activation;
    requires = requires == null ? GatingRequirements.empty() : requires;
    entryPath = Objects.requireNonNull(entryPath, "entryPath");
    if (!entryPath.isAbsolute()) {
      throw new IllegalArgumentException("entryPath must be absolute");
    }
    entryPath = entryPath.normalize();
    relativeEntry = requireRelativePath(relativeEntry, "relativeEntry");
    String expectedRelativeEntry = "skills/" + name + "/SKILL.md";
    if (!expectedRelativeEntry.equals(relativeEntry)) {
      throw new IllegalArgumentException("relativeEntry must equal " + expectedRelativeEntry);
    }
  }

  /** Compatibility constructor for callers that only need the original L1 projection. */
  public SkillMetadata(
      String name,
      String description,
      String license,
      String compatibility,
      Map<String, String> metadata,
      String allowedTools,
      Path entryPath,
      String relativeEntry) {
    this(
        name,
        description,
        license,
        compatibility,
        metadata,
        allowedTools,
        null,
        ActivationCriteria.empty(),
        GatingRequirements.empty(),
        entryPath,
        relativeEntry);
  }

  private static String normalizeOptional(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.strip();
  }

  private static int codePointCount(String value) {
    return value.codePointCount(0, value.length());
  }

  /** Linear-time equivalent of the public Skill identity grammar. */
  static boolean isValidName(String value) {
    try {
      SkillName.parse(value);
      return true;
    } catch (SkillValidationException error) {
      return false;
    }
  }

  private static String requireRelativePath(String value, String field) {
    if (value == null || value.isBlank() || value.indexOf(BACKSLASH) >= 0) {
      throw new IllegalArgumentException(field + " must be a safe relative path");
    }
    Path path = Path.of(value);
    if (path.isAbsolute()) {
      throw new IllegalArgumentException(field + " must be relative");
    }
    for (Path segment : path) {
      String text = segment.toString();
      if (CURRENT_DIRECTORY.equals(text) || PARENT_DIRECTORY.equals(text)) {
        throw new IllegalArgumentException(field + " must not contain dot segments");
      }
    }
    if (!path.normalize().equals(path)) {
      throw new IllegalArgumentException(field + " must be normalized");
    }
    return value;
  }
}
