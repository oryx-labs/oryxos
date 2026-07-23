package io.oryxos.core.skill;

import io.oryxos.core.agent.AgentName;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fresh, bounded filesystem catalog for the managed Skills owned by one Agent. */
public class AgentSkillCatalog {

  private static final String CURRENT_DIRECTORY = ".";
  private static final String PARENT_DIRECTORY = "..";

  private static final Logger LOGGER = LoggerFactory.getLogger(AgentSkillCatalog.class);

  private static final String ENTRYPOINT = "SKILL.md";
  private static final String SKILLS_DIRECTORY = "skills";
  private static final String DISABLED_MARKER = ".oryxos-disabled";
  private static final String ORIGIN_MARKER = ".oryxos-origin.yml";
  private static final String RESERVED_PREFIX = ".oryxos-";
  private static final String CATALOG_HEADER =
      "## Available Skills\n"
          + "Only metadata is loaded. When relevant, call read_file with the entry path.\n\n";
  private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};

  private final Path agentsDir;
  private final SkillMetadataReader metadataReader;
  private final SkillContentValidator contentValidator;
  private final SkillLimits limits;

  public AgentSkillCatalog(
      Path agentsDir,
      SkillMetadataReader metadataReader,
      SkillContentValidator contentValidator,
      SkillLimits limits) {
    this.agentsDir = Objects.requireNonNull(agentsDir, "agentsDir").toAbsolutePath().normalize();
    this.metadataReader = Objects.requireNonNull(metadataReader, "metadataReader");
    this.contentValidator = Objects.requireNonNull(contentValidator, "contentValidator");
    this.limits = Objects.requireNonNull(limits, "limits");
  }

  /** Scans every direct managed candidate and derives its current status and L1 inclusion. */
  public List<SkillDescriptor> list(String agentName) {
    AgentName name = AgentName.parse(agentName);
    return applyBudget(name.value(), scan(name)).descriptors();
  }

  /** Returns one fresh descriptor rather than serving a process-level content cache. */
  public SkillDescriptor get(String agentName, String directoryName) {
    String safeDirectoryName = requireDirectoryName(directoryName);
    return list(agentName).stream()
        .filter(descriptor -> descriptor.directoryName().equals(safeDirectoryName))
        .findFirst()
        .orElseThrow(
            () ->
                new NoSuchElementException(
                    "Skill does not exist: "
                        + safeLogValue(agentName)
                        + "/"
                        + safeLogValue(safeDirectoryName)));
  }

  /** Freezes only enabled, budget-included L1 metadata for one top-level request. */
  public SkillSnapshot snapshot(String agentName) {
    AgentName name = AgentName.parse(agentName);
    BudgetedCatalog catalog = applyBudget(name.value(), scan(name));
    List<SkillMetadata> included =
        catalog.descriptors().stream()
            .filter(SkillDescriptor::catalogIncluded)
            .map(SkillDescriptor::metadata)
            .toList();
    return new SkillSnapshot(
        name.value(), Instant.now(), included, catalog.renderedChars(), catalog.omittedCount());
  }

  /**
   * Revalidates a candidate and simulates enabling it against the aggregate L1 budget without
   * changing its marker. Management code calls this while holding the Agent write lock.
   */
  public void validateCanEnable(String agentName, String directoryName) {
    AgentName name = AgentName.parse(agentName);
    String safeDirectoryName = requireDirectoryName(directoryName);
    List<SkillDescriptor> current = scan(name);
    SkillDescriptor target =
        current.stream()
            .filter(descriptor -> descriptor.directoryName().equals(safeDirectoryName))
            .findFirst()
            .orElseThrow(
                () ->
                    new NoSuchElementException(
                        "Skill does not exist: "
                            + safeLogValue(name.value())
                            + "/"
                            + safeLogValue(safeDirectoryName)));
    if (target.validationError() != null) {
      throw new SkillValidationException(target.validationError());
    }

    List<SkillDescriptor> simulated = new ArrayList<>(current.size());
    for (SkillDescriptor descriptor : current) {
      simulated.add(
          descriptor.directoryName().equals(safeDirectoryName)
              ? copyWithState(descriptor, true, SkillStatus.ENABLED, false)
              : descriptor);
    }
    SkillDescriptor simulatedTarget =
        applyBudget(name.value(), simulated).descriptors().stream()
            .filter(descriptor -> descriptor.directoryName().equals(safeDirectoryName))
            .findFirst()
            .orElseThrow();
    if (!simulatedTarget.catalogIncluded()) {
      throw new SkillValidationException(
          SkillValidationCode.CATALOG_BUDGET_EXCEEDED,
          "Agent Skill catalog exceeds the metadata budget");
    }
  }

  /**
   * Revalidates the current catalog and simulates publishing one new enabled Skill. Management code
   * calls this while holding the Agent write lock and after checking the target path is unused.
   */
  public void validateCanImport(String agentName, SkillMetadata importedMetadata) {
    AgentName name = AgentName.parse(agentName);
    SkillMetadata imported = Objects.requireNonNull(importedMetadata, "importedMetadata");
    List<SkillDescriptor> current = scan(name);
    if (current.size() >= limits.maxSkillsPerAgent()) {
      throw new SkillValidationException(
          SkillValidationCode.TOO_MANY_SKILLS, "Agent has reached the managed Skill limit");
    }

    List<SkillMetadata> enabled =
        current.stream()
            .filter(descriptor -> descriptor.status() == SkillStatus.ENABLED)
            .map(SkillDescriptor::metadata)
            .collect(Collectors.toCollection(ArrayList::new));
    enabled.add(imported);
    enabled.sort(Comparator.comparing(SkillMetadata::name));
    if (renderedCatalogChars(enabled) > limits.maxCatalogChars()) {
      throw new SkillValidationException(
          SkillValidationCode.CATALOG_BUDGET_EXCEEDED,
          "Agent Skill catalog exceeds the metadata budget");
    }
  }

  /** Character count for the exact three-line L1 entry rendered by ContextLoader. */
  static int renderedEntryChars(SkillMetadata metadata) {
    return "- name: ".length()
        + sanitizedLineChars(metadata.name())
        + "\n  description: ".length()
        + sanitizedLineChars(metadata.description())
        + "\n  entry: ".length()
        + sanitizedLineChars(metadata.entryPath().toString())
        + 1;
  }

  static int renderedCatalogChars(List<SkillMetadata> metadata) {
    if (metadata.isEmpty()) {
      return 0;
    }
    return CATALOG_HEADER.length()
        + metadata.stream().mapToInt(AgentSkillCatalog::renderedEntryChars).sum();
  }

  private List<SkillDescriptor> scan(AgentName agentName) {
    Path agentsReal = requireRealDirectory(agentsDir, "Agent root is not a real directory");
    Path agentDir = requireAgentDirectory(agentName, agentsReal);
    Path skillsDir = requireSkillsDirectory(agentName, agentDir);
    if (skillsDir == null) {
      return List.of();
    }
    return scanCandidates(agentName, agentDir, skillsDir);
  }

  private Path requireAgentDirectory(AgentName agentName, Path agentsReal) {
    Path agentDir = agentsDir.resolve(agentName.value()).normalize();
    if (!agentsDir.equals(agentDir.getParent())
        || Files.isSymbolicLink(agentDir)
        || !Files.isDirectory(agentDir, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalStateException("Agent directory does not exist: " + agentName.value());
    }
    Path agentReal =
        requireRealDirectory(agentDir, "Agent directory cannot be inspected: " + agentName.value());
    if (!agentsReal.equals(agentReal.getParent())) {
      throw new IllegalStateException(
          "Agent directory is outside the Agent root: " + agentName.value());
    }
    try {
      agentName.requireFilesystemDirectoryName(agentDir);
    } catch (IllegalArgumentException error) {
      throw new IllegalStateException(
          "Agent directory identity does not match: " + agentName.value(), error);
    }
    return agentDir;
  }

  private Path requireSkillsDirectory(AgentName agentName, Path agentDir) {
    Path skillsDir = agentDir.resolve(SKILLS_DIRECTORY);
    if (!Files.exists(skillsDir, NOFOLLOW)) {
      return null;
    }
    if (Files.isSymbolicLink(skillsDir)
        || !Files.isDirectory(skillsDir, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalStateException("Agent skills path is not a directory: " + agentName.value());
    }
    try {
      if (!FilesystemEntryNames.isStoredAs(skillsDir, SKILLS_DIRECTORY)) {
        LOGGER
            .atWarn()
            .addKeyValue("event", "skill.catalog.non-canonical-root")
            .addKeyValue("agent", safeLogValue(agentName.value()))
            .log("NON_CANONICAL_SKILLS_DIRECTORY_IGNORED");
        return null;
      }
    } catch (IOException error) {
      throw new IllegalStateException(
          "Agent skills path cannot be inspected: " + agentName.value(), error);
    }
    Path skillsReal =
        requireRealDirectory(
            skillsDir, "Agent skills path cannot be inspected: " + agentName.value());
    Path agentReal =
        requireRealDirectory(agentDir, "Agent directory cannot be inspected: " + agentName.value());
    if (!agentReal.equals(skillsReal.getParent())) {
      throw new IllegalStateException(
          "Agent skills path is outside the Agent directory: " + agentName.value());
    }
    return skillsDir;
  }

  private List<SkillDescriptor> scanCandidates(AgentName agentName, Path agentDir, Path skillsDir) {
    try (DirectoryStream<Path> children = Files.newDirectoryStream(skillsDir)) {
      Comparator<SkillCandidate> byDirectoryName =
          Comparator.comparing(SkillCandidate::directoryName);
      PriorityQueue<SkillCandidate> boundedCandidates =
          new PriorityQueue<>(limits.maxCandidatesPerAgent(), byDirectoryName.reversed());
      int candidateCount = 0;
      for (Path child : children) {
        Path fileName = child.getFileName();
        if (fileName == null) {
          LOGGER
              .atWarn()
              .addKeyValue("event", "skill.catalog.invalid-directory")
              .log("INVALID_SKILL_DIRECTORY_IGNORED");
          continue;
        }
        String directoryName = fileName.toString();
        if (Files.isSymbolicLink(child)) {
          warnRootSymlink(agentName.value(), directoryName);
          continue;
        }
        if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
            || !representableDirectoryName(directoryName)
            || !isManagedCandidate(child)) {
          continue;
        }
        candidateCount++;
        SkillCandidate candidate = new SkillCandidate(child, directoryName);
        if (boundedCandidates.size() < limits.maxCandidatesPerAgent()) {
          boundedCandidates.add(candidate);
        } else if (byDirectoryName.compare(candidate, boundedCandidates.element()) < 0) {
          boundedCandidates.remove();
          boundedCandidates.add(candidate);
        }
      }
      if (candidateCount > limits.maxCandidatesPerAgent()) {
        LOGGER
            .atWarn()
            .addKeyValue("event", "skill.catalog.candidates-truncated")
            .addKeyValue("agent", safeLogValue(agentName.value()))
            .addKeyValue("included", limits.maxCandidatesPerAgent())
            .addKeyValue("omitted", candidateCount - limits.maxCandidatesPerAgent())
            .log("CATALOG_CANDIDATES_TRUNCATED");
      }
      List<SkillCandidate> sortedChildren =
          boundedCandidates.stream().sorted(byDirectoryName).toList();
      List<SkillDescriptor> descriptors = new ArrayList<>(sortedChildren.size());
      for (SkillCandidate child : sortedChildren) {
        descriptors.add(
            inspectCandidate(agentName.value(), agentDir, child.path(), child.directoryName()));
      }
      return List.copyOf(descriptors);
    } catch (IOException error) {
      throw new IllegalStateException("Unable to scan Agent skills: " + agentName.value(), error);
    }
  }

  private SkillDescriptor inspectCandidate(
      String agentName, Path agentDir, Path skillDir, String directoryName) {
    Path entrypoint = skillDir.resolve(ENTRYPOINT);
    boolean hasEntrypoint = hasCanonicalEntrypoint(entrypoint);
    boolean configuredEnabled = !Files.exists(skillDir.resolve(DISABLED_MARKER), NOFOLLOW);
    SkillSource source =
        Files.exists(skillDir.resolve(ORIGIN_MARKER), NOFOLLOW)
            ? SkillSource.UPLOAD
            : SkillSource.WORKSPACE;
    SkillMetadata metadata = null;
    SkillContentValidator.ContentStats stats =
        new SkillContentValidator.ContentStats(List.of(), 0, 0);
    SkillValidationError validationError = null;
    Instant updatedAt = boundedModifiedAt(skillDir);

    try {
      stats =
          Objects.requireNonNull(
              contentValidator.validate(skillDir, limits), "contentValidator result");
      updatedAt = latestModifiedAt(skillDir, stats.resources());
      if (!hasEntrypoint) {
        validationError =
            new SkillValidationError(SkillValidationCode.MISSING_ENTRYPOINT, "SKILL.md is missing");
      } else {
        metadata =
            Objects.requireNonNull(
                metadataReader.read(agentDir, skillDir, limits), "metadataReader result");
      }
    } catch (SkillValidationException error) {
      validationError = error.error();
    } catch (IOException | RuntimeException error) {
      LOGGER
          .atWarn()
          .addKeyValue("event", "skill.catalog.candidate-unreadable")
          .addKeyValue("agent", safeLogValue(agentName))
          .addKeyValue("skill", safeLogValue(directoryName))
          .addKeyValue("errorType", error.getClass().getSimpleName())
          .log("CONTENT_UNREADABLE");
      validationError =
          new SkillValidationError(
              SkillValidationCode.CONTENT_UNREADABLE,
              "Skill package could not be inspected safely");
    }

    SkillStatus status = SkillStatus.resolve(configuredEnabled, validationError);
    String relativeEntrypoint = hasEntrypoint ? "skills/" + directoryName + "/" + ENTRYPOINT : null;
    return new SkillDescriptor(
        agentName,
        directoryName,
        metadata,
        status,
        configuredEnabled,
        source,
        updatedAt,
        validationError,
        relativeEntrypoint,
        stats.resources(),
        stats.fileCount(),
        stats.totalBytes(),
        false);
  }

  private BudgetedCatalog applyBudget(String agentName, List<SkillDescriptor> descriptors) {
    List<SkillDescriptor> eligible =
        descriptors.stream()
            .filter(descriptor -> descriptor.status() == SkillStatus.ENABLED)
            .sorted(Comparator.comparing(descriptor -> descriptor.metadata().name()))
            .toList();
    Set<String> includedNames = new HashSet<>();
    int renderedChars = 0;
    int omittedCount = 0;
    boolean exhausted = false;
    for (SkillDescriptor descriptor : eligible) {
      int entryChars = renderedEntryChars(descriptor.metadata());
      int headerChars = includedNames.isEmpty() ? CATALOG_HEADER.length() : 0;
      boolean fits =
          !exhausted
              && includedNames.size() < limits.maxSkillsPerAgent()
              && (long) renderedChars + headerChars + entryChars <= limits.maxCatalogChars();
      if (fits) {
        includedNames.add(descriptor.directoryName());
        renderedChars += headerChars + entryChars;
      } else {
        exhausted = true;
        omittedCount++;
      }
    }

    List<SkillDescriptor> budgeted =
        descriptors.stream()
            .map(
                descriptor ->
                    copyWithCatalogIncluded(
                        descriptor, includedNames.contains(descriptor.directoryName())))
            .toList();
    if (omittedCount > 0) {
      LOGGER
          .atWarn()
          .addKeyValue("event", "skill.catalog.truncated")
          .addKeyValue("agent", safeLogValue(agentName))
          .addKeyValue("included", includedNames.size())
          .addKeyValue("omitted", omittedCount)
          .log("CATALOG_TRUNCATED");
    }
    return new BudgetedCatalog(budgeted, renderedChars, omittedCount);
  }

  private static SkillDescriptor copyWithCatalogIncluded(
      SkillDescriptor descriptor, boolean included) {
    return new SkillDescriptor(
        descriptor.agentName(),
        descriptor.directoryName(),
        descriptor.metadata(),
        descriptor.status(),
        descriptor.configuredEnabled(),
        descriptor.source(),
        descriptor.updatedAt(),
        descriptor.validationError(),
        descriptor.relativeEntrypoint(),
        descriptor.resources(),
        descriptor.fileCount(),
        descriptor.totalBytes(),
        included);
  }

  private static SkillDescriptor copyWithState(
      SkillDescriptor descriptor,
      boolean configuredEnabled,
      SkillStatus status,
      boolean catalogIncluded) {
    return new SkillDescriptor(
        descriptor.agentName(),
        descriptor.directoryName(),
        descriptor.metadata(),
        status,
        configuredEnabled,
        descriptor.source(),
        descriptor.updatedAt(),
        descriptor.validationError(),
        descriptor.relativeEntrypoint(),
        descriptor.resources(),
        descriptor.fileCount(),
        descriptor.totalBytes(),
        catalogIncluded);
  }

  private static Instant latestModifiedAt(Path skillDir, List<String> resources)
      throws IOException {
    // ContentStats is already bounded by maxEntries/maxDepth. Reusing its paths avoids a second
    // unbounded walk if an out-of-process writer races the scan after validation.
    Set<Path> candidates = new LinkedHashSet<>();
    candidates.add(skillDir);
    candidates.add(skillDir.resolve(ENTRYPOINT));
    for (String resource : resources) {
      Path candidate = skillDir.resolve(resource).normalize();
      if (!candidate.startsWith(skillDir)) {
        throw new IOException("validated resource escaped Skill root");
      }
      candidates.add(candidate);
      Path parent = candidate.getParent();
      while (parent != null && parent.startsWith(skillDir)) {
        candidates.add(parent);
        if (parent.equals(skillDir)) {
          break;
        }
        parent = parent.getParent();
      }
    }
    candidates.add(skillDir.resolve(DISABLED_MARKER));
    candidates.add(skillDir.resolve(ORIGIN_MARKER));

    FileTime latest = Files.getLastModifiedTime(skillDir, LinkOption.NOFOLLOW_LINKS);
    for (Path candidate : candidates) {
      if (!Files.exists(candidate, NOFOLLOW)) {
        continue;
      }
      FileTime modified = Files.getLastModifiedTime(candidate, LinkOption.NOFOLLOW_LINKS);
      if (modified.compareTo(latest) > 0) {
        latest = modified;
      }
    }
    return latest.toInstant();
  }

  private Instant boundedModifiedAt(Path skillDir) {
    // Invalid candidates do not return ContentStats, but the management timestamp must still
    // reflect a nested resource edit that caused validation to fail. Files.walk does not follow
    // links by default; both depth and visited-entry count use the same configured package bounds.
    try (Stream<Path> paths = Files.walk(skillDir, limits.maxDepth())) {
      return paths
          .limit((long) limits.maxEntries() + 1)
          .map(AgentSkillCatalog::safeModifiedAt)
          .max(Comparator.naturalOrder())
          .orElse(Instant.EPOCH);
    } catch (IOException | RuntimeException error) {
      return safeModifiedAt(skillDir);
    }
  }

  private static Instant safeModifiedAt(Path path) {
    try {
      return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant();
    } catch (IOException | RuntimeException error) {
      return Instant.EPOCH;
    }
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "Locale.ROOT case folding is deliberate: every Unicode case alias of the reserved "
              + ".oryxos- prefix must remain managed and validated, never treated as legacy content.")
  private static boolean isManagedCandidate(Path skillDir) {
    try (DirectoryStream<Path> members = Files.newDirectoryStream(skillDir)) {
      for (Path member : members) {
        String filename = String.valueOf(member.getFileName());
        if (ENTRYPOINT.equalsIgnoreCase(filename)
            || filename.toLowerCase(Locale.ROOT).startsWith(RESERVED_PREFIX)) {
          return true;
        }
      }
      return false;
    } catch (IOException | SecurityException error) {
      // Treat an unreadable direct child as a candidate so inspectCandidate isolates it as invalid
      // instead of silently converting a formerly managed package into legacy content.
      return true;
    }
  }

  private static boolean hasCanonicalEntrypoint(Path entrypoint) {
    if (!Files.exists(entrypoint, NOFOLLOW)) {
      return false;
    }
    try {
      return FilesystemEntryNames.isStoredAs(entrypoint, ENTRYPOINT);
    } catch (IOException | SecurityException error) {
      return false;
    }
  }

  private static Path requireRealDirectory(Path directory, String safeMessage) {
    if (Files.isSymbolicLink(directory)
        || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalStateException(safeMessage);
    }
    try {
      Path real = directory.toRealPath();
      // Recheck after canonicalization so a link replacement during the first inspection is not
      // silently accepted as a trusted catalog root.
      if (Files.isSymbolicLink(directory)
          || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalStateException(safeMessage);
      }
      return real;
    } catch (IOException error) {
      throw new IllegalStateException(safeMessage, error);
    }
  }

  private static boolean representableDirectoryName(String name) {
    if (CURRENT_DIRECTORY.equals(name)
        || PARENT_DIRECTORY.equals(name)
        || name.indexOf('\\') >= 0
        || name.codePoints().anyMatch(AgentSkillCatalog::unsafeTextCodePoint)) {
      LOGGER
          .atWarn()
          .addKeyValue("event", "skill.catalog.invalid-directory")
          .log("INVALID_SKILL_DIRECTORY_IGNORED");
      return false;
    }
    return true;
  }

  private static String requireDirectoryName(String directoryName) {
    if (directoryName == null
        || directoryName.isBlank()
        || CURRENT_DIRECTORY.equals(directoryName)
        || PARENT_DIRECTORY.equals(directoryName)
        || directoryName.indexOf('/') >= 0
        || directoryName.indexOf('\\') >= 0
        || directoryName.codePoints().anyMatch(AgentSkillCatalog::unsafeTextCodePoint)) {
      throw new IllegalArgumentException("Skill name must be one safe directory segment");
    }
    return directoryName;
  }

  private static void warnRootSymlink(String agentName, String directoryName) {
    LOGGER
        .atWarn()
        .addKeyValue("event", "skill.catalog.root-symlink")
        .addKeyValue("agent", safeLogValue(agentName))
        .addKeyValue("skill", safeLogValue(directoryName))
        .log("ROOT_SYMLINK_IGNORED");
  }

  private static String safeLogValue(String value) {
    if (value == null) {
      return "unresolved";
    }
    StringBuilder safe = new StringBuilder(Math.min(value.length(), 128));
    value
        .codePoints()
        .limit(128)
        .forEach(
            codePoint -> safe.appendCodePoint(unsafeTextCodePoint(codePoint) ? '_' : codePoint));
    return safe.toString();
  }

  private static boolean unsafeTextCodePoint(int codePoint) {
    int type = Character.getType(codePoint);
    return Character.isISOControl(codePoint)
        || type == Character.FORMAT
        || type == Character.LINE_SEPARATOR
        || type == Character.PARAGRAPH_SEPARATOR;
  }

  private static int sanitizedLineChars(String value) {
    int chars = 0;
    for (int offset = 0; offset < value.length(); ) {
      int codePoint = value.codePointAt(offset);
      int type = Character.getType(codePoint);
      chars +=
          Character.isISOControl(codePoint)
                  || type == Character.FORMAT
                  || type == Character.LINE_SEPARATOR
                  || type == Character.PARAGRAPH_SEPARATOR
              ? 1
              : Character.charCount(codePoint);
      offset += Character.charCount(codePoint);
    }
    return chars;
  }

  private record BudgetedCatalog(
      List<SkillDescriptor> descriptors, int renderedChars, int omittedCount) {
    private BudgetedCatalog {
      descriptors = List.copyOf(descriptors);
    }
  }

  private record SkillCandidate(Path path, String directoryName) {}
}
