package io.oryxos.core.skill;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fresh, no-follow catalog over direct public packages in {@code .oryxos/skills}. */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "CRLF_INJECTION_LOGS",
    justification =
        "Logged Skill names have already passed the strict single-line SkillName grammar.")
public final class PublicSkillCatalog {

  private static final Logger LOG = LoggerFactory.getLogger(PublicSkillCatalog.class);
  private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};
  private static final String ENTRYPOINT = "SKILL.md";
  private static final String DISABLED = ".oryxos-disabled";
  private static final String ORIGIN = ".oryxos-origin.yml";

  private final Path workspaceRoot;
  private final Path skillsRoot;
  private final SkillMetadataReader metadataReader;
  private final SkillContentValidator contentValidator;
  private final SkillLimits limits;

  public PublicSkillCatalog(
      Path workspaceRoot,
      SkillMetadataReader metadataReader,
      SkillContentValidator contentValidator,
      SkillLimits limits) {
    this.workspaceRoot =
        Objects.requireNonNull(workspaceRoot, "workspaceRoot").toAbsolutePath().normalize();
    this.skillsRoot = this.workspaceRoot.resolve("skills");
    this.metadataReader = Objects.requireNonNull(metadataReader, "metadataReader");
    this.contentValidator = Objects.requireNonNull(contentValidator, "contentValidator");
    this.limits = Objects.requireNonNull(limits, "limits");
  }

  public List<PublicSkillDescriptor> list() {
    requireRealDirectory(skillsRoot, "Public Skill root is unavailable");
    List<PublicSkillDescriptor> descriptors = new ArrayList<>();
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(skillsRoot)) {
      for (Path entry : entries) {
        if (Files.isSymbolicLink(entry)
            || !Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
            || !isManagedCandidate(entry)) {
          continue;
        }
        String directoryName = String.valueOf(entry.getFileName());
        try {
          SkillName.parse(directoryName);
        } catch (SkillValidationException error) {
          LOG.warn("event=skill.catalog.invalid-name reasonCode=INVALID_NAME");
          continue;
        }
        descriptors.add(inspect(entry, directoryName));
      }
    } catch (IOException error) {
      throw new IllegalStateException("Public Skill root cannot be scanned", error);
    }
    return descriptors.stream().sorted(Comparator.comparing(PublicSkillDescriptor::name)).toList();
  }

  public PublicSkillDescriptor get(String skillName) {
    String name = SkillName.parse(skillName).value();
    return list().stream()
        .filter(skill -> skill.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new NoSuchElementException("Skill does not exist: " + name));
  }

  /** Requires valid content but permits a globally disabled package to remain associated. */
  public PublicSkillDescriptor requireLoadable(String skillName) {
    PublicSkillDescriptor descriptor = get(skillName);
    if (descriptor.status() == SkillStatus.INVALID) {
      throw new SkillValidationException(descriptor.validationError());
    }
    return descriptor;
  }

  public Path packagePath(String skillName) {
    String name = SkillName.parse(skillName).value();
    Path path = skillsRoot.resolve(name).normalize();
    if (!skillsRoot.equals(path.getParent())) {
      throw new IllegalArgumentException("Skill path is outside the public root");
    }
    return path;
  }

  public Path skillsRoot() {
    return skillsRoot;
  }

  private PublicSkillDescriptor inspect(Path skillDir, String directoryName) {
    boolean configuredEnabled = !Files.exists(skillDir.resolve(DISABLED), NOFOLLOW);
    SkillSource source =
        Files.exists(skillDir.resolve(ORIGIN), NOFOLLOW)
            ? SkillSource.UPLOAD
            : SkillSource.WORKSPACE;
    SkillMetadata metadata = null;
    SkillValidationError error = null;
    SkillContentValidator.ContentStats stats =
        new SkillContentValidator.ContentStats(List.of(), 0, 0);
    try {
      stats = contentValidator.validate(skillDir, limits);
      metadata = metadataReader.read(workspaceRoot, skillDir, limits);
      if (!directoryName.equals(metadata.name())) {
        throw new SkillValidationException(
            SkillValidationCode.NAME_DIRECTORY_MISMATCH,
            "Skill manifest name does not match its public directory");
      }
    } catch (SkillValidationException validation) {
      error = validation.error();
    } catch (RuntimeException unexpected) {
      LOG.warn("event=skill.catalog.invalid skill={} reasonCode=CONTENT_UNREADABLE", directoryName);
      error =
          new SkillValidationError(
              SkillValidationCode.CONTENT_UNREADABLE,
              "Skill package could not be inspected safely");
    }
    SkillStatus status = SkillStatus.resolve(configuredEnabled, error);
    return new PublicSkillDescriptor(
        directoryName,
        metadata,
        status,
        configuredEnabled,
        source,
        latestModifiedAt(skillDir),
        error,
        metadata == null ? null : "skills/" + directoryName + "/" + ENTRYPOINT,
        stats.resources(),
        stats.fileCount(),
        stats.totalBytes(),
        List.of());
  }

  private boolean isManagedCandidate(Path path) {
    return Files.exists(path.resolve(ENTRYPOINT), NOFOLLOW)
        || Files.exists(path.resolve(DISABLED), NOFOLLOW)
        || Files.exists(path.resolve(ORIGIN), NOFOLLOW);
  }

  private Instant latestModifiedAt(Path root) {
    try (Stream<Path> paths = Files.walk(root, limits.maxDepth())) {
      return paths
          .limit((long) limits.maxEntries() + 1)
          .map(PublicSkillCatalog::modifiedAt)
          .max(Comparator.naturalOrder())
          .orElse(Instant.EPOCH);
    } catch (IOException | RuntimeException error) {
      return modifiedAt(root);
    }
  }

  private static Instant modifiedAt(Path path) {
    try {
      return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant();
    } catch (IOException error) {
      return Instant.EPOCH;
    }
  }

  private static Path requireRealDirectory(Path path, String safeMessage) {
    if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalStateException(safeMessage);
    }
    try {
      Path real = path.toRealPath();
      if (Files.isSymbolicLink(path)) {
        throw new IllegalStateException(safeMessage);
      }
      return real;
    } catch (IOException error) {
      throw new IllegalStateException(safeMessage, error);
    }
  }
}
