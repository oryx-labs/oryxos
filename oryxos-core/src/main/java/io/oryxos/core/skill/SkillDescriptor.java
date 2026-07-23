package io.oryxos.core.skill;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable management view of one managed Skill package, including invalid candidates. */
public record SkillDescriptor(
    String agentName,
    String directoryName,
    SkillMetadata metadata,
    SkillStatus status,
    boolean configuredEnabled,
    SkillSource source,
    Instant updatedAt,
    SkillValidationError validationError,
    String relativeEntrypoint,
    List<String> resources,
    int fileCount,
    long totalBytes,
    boolean catalogIncluded) {

  private static final String CURRENT_DIRECTORY = ".";
  private static final String PARENT_DIRECTORY = "..";
  private static final char FORWARD_SLASH = '/';
  private static final char BACKSLASH = '\\';

  public SkillDescriptor {
    agentName = requireNonBlank(agentName, "agentName");
    directoryName = requireDirectoryName(directoryName);
    status = Objects.requireNonNull(status, "status");
    source = Objects.requireNonNull(source, "source");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    SkillStatus expectedStatus = SkillStatus.resolve(configuredEnabled, validationError);
    if (status != expectedStatus) {
      throw new IllegalArgumentException(
          "status " + status + " is inconsistent with validation and configuredEnabled");
    }
    if (status != SkillStatus.INVALID && metadata == null) {
      throw new IllegalArgumentException("valid Skill descriptor requires metadata");
    }
    if (status != SkillStatus.INVALID && !directoryName.equals(metadata.name())) {
      throw new IllegalArgumentException("valid Skill name must match its directory");
    }
    if (status != SkillStatus.INVALID && relativeEntrypoint == null) {
      throw new IllegalArgumentException("valid Skill descriptor requires an entrypoint");
    }
    if (relativeEntrypoint != null) {
      relativeEntrypoint = requireRelativePath(relativeEntrypoint, "relativeEntrypoint");
    }
    if (metadata != null
        && relativeEntrypoint != null
        && !metadata.relativeEntry().equals(relativeEntrypoint)) {
      throw new IllegalArgumentException("relativeEntrypoint does not match metadata");
    }
    resources =
        resources == null
            ? List.of()
            : resources.stream()
                .map(resource -> requireRelativePath(resource, "resource"))
                .sorted(SkillContentValidator::compareCodePoints)
                .toList();
    if (fileCount < 0) {
      throw new IllegalArgumentException("fileCount must not be negative");
    }
    if (totalBytes < 0) {
      throw new IllegalArgumentException("totalBytes must not be negative");
    }
    if (catalogIncluded && status != SkillStatus.ENABLED) {
      throw new IllegalArgumentException("only an enabled Skill may be included in the catalog");
    }
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "The canonical constructor stores a sorted unmodifiable list whose String elements are immutable.")
  public List<String> resources() {
    return resources;
  }

  private static String requireDirectoryName(String value) {
    String name = requireNonBlank(value, "directoryName");
    if (CURRENT_DIRECTORY.equals(name)
        || PARENT_DIRECTORY.equals(name)
        || name.indexOf(FORWARD_SLASH) >= 0
        || name.indexOf(BACKSLASH) >= 0) {
      throw new IllegalArgumentException("directoryName must be one safe path segment");
    }
    return name;
  }

  private static String requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
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
