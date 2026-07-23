package io.oryxos.core.skill;

import io.oryxos.core.agent.AgentName;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

/** Read-side catalog facade and atomic lifecycle manager for Agent-local Skills. */
public final class SkillManagementService {

  private static final Logger LOGGER = LoggerFactory.getLogger(SkillManagementService.class);
  private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};
  private static final Pattern ARCHIVE_EVENT_NAME =
      Pattern.compile(
          "[0-9]{8}T[0-9]{6}Z-"
              + "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
              + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private static final Pattern STABLE_REASON_CODE = Pattern.compile("[A-Z0-9_]{1,64}");
  private static final String ARCHIVE_PACKAGE_DIRECTORY = "package";
  private static final String SKILLS_DIRECTORY = "skills";

  private final Path agentsDir;
  private final Path oryxosRoot;
  private final ProfileRegistry profiles;
  private final AgentSkillCatalog catalog;
  private final SkillPackageImporter importer;
  private final AgentSkillLockRegistry locks;
  private final PublishOperations publishOperations;
  private final ArchiveOperations archiveOperations;
  private final Clock clock;
  private final Supplier<UUID> uuidSupplier;

  public SkillManagementService(
      Path agentsDir,
      ProfileRegistry profiles,
      AgentSkillCatalog catalog,
      SkillPackageImporter importer,
      AgentSkillLockRegistry locks) {
    this(
        agentsDir,
        profiles,
        catalog,
        importer,
        locks,
        new NioPublishOperations(),
        new NioArchiveOperations(),
        Clock.systemUTC(),
        UUID::randomUUID);
  }

  SkillManagementService(
      Path agentsDir,
      ProfileRegistry profiles,
      AgentSkillCatalog catalog,
      SkillPackageImporter importer,
      AgentSkillLockRegistry locks,
      PublishOperations publishOperations) {
    this(
        agentsDir,
        profiles,
        catalog,
        importer,
        locks,
        publishOperations,
        new NioArchiveOperations(),
        Clock.systemUTC(),
        UUID::randomUUID);
  }

  SkillManagementService(
      Path agentsDir,
      ProfileRegistry profiles,
      AgentSkillCatalog catalog,
      SkillPackageImporter importer,
      AgentSkillLockRegistry locks,
      PublishOperations publishOperations,
      ArchiveOperations archiveOperations,
      Clock clock,
      Supplier<UUID> uuidSupplier) {
    this.agentsDir = Objects.requireNonNull(agentsDir, "agentsDir").toAbsolutePath().normalize();
    this.oryxosRoot =
        Objects.requireNonNull(this.agentsDir.getParent(), "agentsDir must have a parent");
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.importer = Objects.requireNonNull(importer, "importer");
    this.locks = Objects.requireNonNull(locks, "locks");
    this.publishOperations = Objects.requireNonNull(publishOperations, "publishOperations");
    this.archiveOperations = Objects.requireNonNull(archiveOperations, "archiveOperations");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.uuidSupplier = Objects.requireNonNull(uuidSupplier, "uuidSupplier");
  }

  /** Returns a fresh immutable descriptor list while holding a short Agent read lock. */
  public List<SkillDescriptor> list(String agentName) {
    AgentName name = AgentName.parse(agentName);
    requireCurrentAgent(name);
    return locks.withReadLock(
        name.value(),
        () -> {
          requireCurrentAgent(name);
          return catalog.list(name.value());
        });
  }

  /** Returns one fresh descriptor while holding a short Agent read lock. */
  public SkillDescriptor get(String agentName, String directoryName) {
    AgentName name = AgentName.parse(agentName);
    requireCurrentAgent(name);
    return locks.withReadLock(
        name.value(),
        () -> {
          requireCurrentAgent(name);
          return catalog.get(name.value(), directoryName);
        });
  }

  /**
   * Fully prepares an upload without the write lock, then publishes it in one atomic move after all
   * mutable Agent state has been revalidated under that lock.
   */
  public SkillDescriptor importSkill(String agentName, InputStream zip, String originalFilename) {
    MutationAudit audit = MutationAudit.importStarted();
    PreparedSkill prepared = null;
    try {
      AgentName name = AgentName.parse(agentName);
      audit.agent(name.value());
      requireCurrentAgent(name);
      if (zip == null) {
        throw new IllegalArgumentException("Skill upload must not be null");
      }

      prepared = importer.prepare(zip, originalFilename);
      audit.skill(prepared.metadata().name());
      PreparedSkill ready = prepared;
      SkillDescriptor imported =
          locks.withWriteLock(name.value(), () -> publishPrepared(name, ready));
      audit.success();
      return imported;
    } catch (SkillImportException error) {
      audit.rejected(error.reasonCode());
      throw error;
    } catch (SkillValidationException error) {
      audit.rejected(error.code().name());
      throw error;
    } catch (NoSuchElementException error) {
      audit.rejected("AGENT_NOT_FOUND");
      throw error;
    } catch (IllegalArgumentException error) {
      audit.rejected("INVALID_REQUEST");
      throw error;
    } catch (PublishException error) {
      audit.failed(error.reasonCode());
      throw error;
    } catch (RuntimeException error) {
      audit.failed("INTERNAL_ERROR");
      throw error;
    } finally {
      if (prepared != null) {
        try {
          importer.discard(prepared);
        } catch (RuntimeException ignored) {
          // The importer contract makes discard idempotent and best-effort. In particular, a
          // cleanup problem after the atomic publish must not turn an already-visible Skill into a
          // failed response. The orphan cleaner can retry this event on startup.
          LOGGER
              .atWarn()
              .addKeyValue("event", "skill.staging.cleanup-failed")
              .addKeyValue("agent", audit.agent())
              .addKeyValue("skill", audit.skill())
              .log("STAGING_CLEANUP_FAILED");
        }
      }
      audit.log();
    }
  }

  /** Changes the persisted administrator setting while holding the Agent write lock. */
  public SkillDescriptor setEnabled(String agentName, String directoryName, boolean enabled) {
    MutationAudit audit = MutationAudit.started(enabled ? "enable" : "disable");
    try {
      AgentName name = AgentName.parse(agentName);
      audit.agent(name.value());
      requireCurrentAgent(name);
      SkillDescriptor updated =
          locks.withWriteLock(
              name.value(), () -> setEnabledLocked(name, directoryName, enabled, audit));
      audit.success();
      return updated;
    } catch (SkillValidationException error) {
      audit.rejected(error.code().name());
      throw error;
    } catch (NoSuchElementException error) {
      audit.rejected("NOT_FOUND");
      throw error;
    } catch (IllegalArgumentException error) {
      audit.rejected("INVALID_REQUEST");
      throw error;
    } catch (PublishException error) {
      audit.failed(error.reasonCode());
      throw error;
    } catch (RuntimeException error) {
      audit.failed("INTERNAL_ERROR");
      throw error;
    } finally {
      audit.log();
    }
  }

  /** Atomically moves one managed package into an immutable archive event. */
  public void delete(String agentName, String directoryName) {
    MutationAudit audit = MutationAudit.started("delete");
    try {
      AgentName name = AgentName.parse(agentName);
      audit.agent(name.value());
      requireCurrentAgent(name);
      locks.withWriteLock(
          name.value(),
          () -> {
            deleteLocked(name, directoryName, audit);
            return null;
          });
      audit.success();
    } catch (SkillValidationException error) {
      audit.rejected(error.code().name());
      throw error;
    } catch (NoSuchElementException error) {
      audit.rejected("NOT_FOUND");
      throw error;
    } catch (IllegalArgumentException error) {
      audit.rejected("INVALID_REQUEST");
      throw error;
    } catch (PublishException error) {
      audit.failed(error.reasonCode());
      throw error;
    } catch (RuntimeException error) {
      audit.failed("INTERNAL_ERROR");
      throw error;
    } finally {
      audit.log();
    }
  }

  /** Removes only expired, incomplete archive events; completed package/ events are immutable. */
  public void cleanupArchiveOrphans(Duration ttl) {
    Objects.requireNonNull(ttl, "ttl");
    if (ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("Archive cleanup TTL must be positive");
    }

    Path archivePath = oryxosRoot.resolve("archive");
    if (!Files.exists(archivePath, NOFOLLOW)) {
      return;
    }
    Path rootReal = requireRealDirectory(oryxosRoot, "UNSAFE_WORKSPACE_ROOT");
    ArchiveDirectory root = new ArchiveDirectory(oryxosRoot, rootReal);
    ArchiveDirectory archive =
        requireExistingArchiveDirectory(root, archivePath, "UNSAFE_ARCHIVE_ROOT");
    Path skillsPath = archive.path().resolve(".skills");
    if (!Files.exists(skillsPath, NOFOLLOW)) {
      return;
    }
    ArchiveDirectory archiveSkills =
        requireExistingArchiveDirectory(archive, skillsPath, "UNSAFE_ARCHIVE_ROOT");

    try (DirectoryStream<Path> agentDirectories = Files.newDirectoryStream(archiveSkills.path())) {
      for (Path agentDirectory : agentDirectories) {
        cleanupArchiveAgent(agentDirectory, archiveSkills, ttl);
      }
    } catch (IOException | SecurityException error) {
      throw new PublishException(
          "ARCHIVE_CLEANUP_FAILED", "Archive namespace could not be inspected safely");
    }
  }

  private SkillDescriptor setEnabledLocked(
      AgentName name, String directoryName, boolean enabled, MutationAudit audit) {
    ManagedSkill managed = requireManagedSkill(name, directoryName);
    audit.skill(managed.descriptor().directoryName());
    Path marker = managed.skillDir().resolve(".oryxos-disabled");

    if (enabled) {
      // validateCanEnable performs a fresh full package scan and simulates the aggregate L1 budget.
      // The persisted disabled state changes only after both checks have succeeded.
      catalog.validateCanEnable(name.value(), managed.descriptor().directoryName());
      boolean changed = deleteDisabledMarker(marker);
      return copyWithAdministratorState(managed.descriptor(), true, changed, true);
    }

    boolean changed = createDisabledMarker(marker);
    return copyWithAdministratorState(managed.descriptor(), false, changed, false);
  }

  private void deleteLocked(AgentName name, String directoryName, MutationAudit audit) {
    ManagedSkill managed = requireManagedSkill(name, directoryName);
    audit.skill(managed.descriptor().directoryName());
    ArchivedSkill archived =
        ArchivedSkill.create(
            name.value(),
            managed.descriptor().directoryName(),
            managed.descriptor().source(),
            clock.instant());
    archive(managed, archived);
  }

  private ManagedSkill requireManagedSkill(AgentName name, String directoryName) {
    AgentPaths paths = requireCurrentAgent(name);
    if (!Files.exists(paths.skillsDir(), NOFOLLOW)) {
      throw new NoSuchElementException("Skill does not exist");
    }
    Path skillsDir = requireExistingSkillsDirectory(paths);
    SkillDescriptor descriptor = catalog.get(name.value(), directoryName);
    Path skillDir = skillsDir.resolve(descriptor.directoryName()).normalize();
    if (!skillsDir.equals(skillDir.getParent()) || !Files.exists(skillDir, NOFOLLOW)) {
      throw new NoSuchElementException("Skill does not exist");
    }
    Path skillsReal = requireRealDirectory(skillsDir, "UNSAFE_SKILLS_DIRECTORY");
    Path skillReal = requireRealDirectory(skillDir, "UNSAFE_SKILL_DIRECTORY");
    if (!skillsReal.equals(skillReal.getParent())) {
      throw new PublishException(
          "UNSAFE_SKILL_DIRECTORY", "Skill directory is outside the Agent skills directory");
    }
    return new ManagedSkill(descriptor, skillDir);
  }

  private boolean createDisabledMarker(Path marker) {
    if (Files.exists(marker, NOFOLLOW)) {
      return false;
    }
    try {
      Files.createFile(marker);
      return true;
    } catch (FileAlreadyExistsException error) {
      if (isZeroByteRegularFile(marker)) {
        return false;
      }
      throw new PublishException(
          "SKILL_STATE_CHANGE_FAILED", "Skill disabled marker changed concurrently");
    } catch (IOException | SecurityException | UnsupportedOperationException error) {
      throw new PublishException(
          "SKILL_STATE_CHANGE_FAILED", "Skill disabled marker could not be created");
    }
  }

  private boolean deleteDisabledMarker(Path marker) {
    if (!Files.exists(marker, NOFOLLOW)) {
      return false;
    }
    if (!isZeroByteRegularFile(marker)) {
      throw new SkillValidationException(
          SkillValidationCode.RESERVED_FILE_INVALID,
          ".oryxos-disabled must be an empty regular file");
    }
    try {
      return Files.deleteIfExists(marker);
    } catch (IOException | SecurityException | UnsupportedOperationException error) {
      throw new PublishException(
          "SKILL_STATE_CHANGE_FAILED", "Skill disabled marker could not be removed");
    }
  }

  private static boolean isZeroByteRegularFile(Path file) {
    if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }
    try {
      return Files.size(file) == 0;
    } catch (IOException | SecurityException error) {
      return false;
    }
  }

  private SkillDescriptor copyWithAdministratorState(
      SkillDescriptor descriptor,
      boolean configuredEnabled,
      boolean changed,
      boolean catalogIncluded) {
    SkillStatus status = SkillStatus.resolve(configuredEnabled, descriptor.validationError());
    return new SkillDescriptor(
        descriptor.agentName(),
        descriptor.directoryName(),
        descriptor.metadata(),
        status,
        configuredEnabled,
        descriptor.source(),
        changed ? clock.instant() : descriptor.updatedAt(),
        descriptor.validationError(),
        descriptor.relativeEntrypoint(),
        descriptor.resources(),
        descriptor.fileCount(),
        descriptor.totalBytes(),
        catalogIncluded && status == SkillStatus.ENABLED);
  }

  private void cleanupArchiveAgent(
      Path agentDirectory, ArchiveDirectory archiveSkills, Duration ttl) {
    Path fileName = agentDirectory.getFileName();
    if (fileName == null || !isDirectRealDirectory(agentDirectory, archiveSkills)) {
      warnUnsafeArchiveEntry();
      return;
    }
    AgentName agentName;
    try {
      agentName = AgentName.parse(fileName.toString());
    } catch (IllegalArgumentException ignored) {
      warnUnsafeArchiveEntry();
      return;
    }

    try {
      locks.withWriteLock(
          agentName.value(),
          () -> {
            ArchiveDirectory archiveAgent =
                requireExistingArchiveDirectory(
                    archiveSkills, agentDirectory, "UNSAFE_ARCHIVE_AGENT");
            cleanupExpiredArchiveEvents(archiveAgent, agentName.value(), ttl);
            return null;
          });
    } catch (RuntimeException ignored) {
      // Cleanup is startup hygiene. A malformed or concurrently replaced archive child must not
      // prevent the runtime from starting; the active Skill namespace is never touched here.
      warnUnsafeArchiveEntry();
    }
  }

  private static boolean isDirectRealDirectory(Path candidate, ArchiveDirectory expectedParent) {
    try {
      BasicFileAttributes attributes =
          Files.readAttributes(candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      return !attributes.isSymbolicLink()
          && attributes.isDirectory()
          && expectedParent.real().equals(candidate.toRealPath().getParent());
    } catch (IOException | SecurityException error) {
      return false;
    }
  }

  private static void warnUnsafeArchiveEntry() {
    LOGGER
        .atWarn()
        .addKeyValue("event", "skill.archive.cleanup-unsafe-entry")
        .log("ARCHIVE_CLEANUP_ENTRY_IGNORED");
  }

  private void cleanupExpiredArchiveEvents(
      ArchiveDirectory archiveAgent, String agentName, Duration ttl) {
    var cutoff = clock.instant().minus(ttl);
    try (DirectoryStream<Path> events = Files.newDirectoryStream(archiveAgent.path())) {
      for (Path eventDir : events) {
        Path fileName = eventDir.getFileName();
        if (fileName == null || !ARCHIVE_EVENT_NAME.matcher(fileName.toString()).matches()) {
          continue;
        }
        BasicFileAttributes attributes =
            Files.readAttributes(eventDir, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink()
            || !attributes.isDirectory()
            || !attributes.lastModifiedTime().toInstant().isBefore(cutoff)) {
          continue;
        }
        Path eventReal = eventDir.toRealPath();
        if (!archiveAgent.real().equals(eventReal.getParent())
            || Files.exists(eventDir.resolve("package"), NOFOLLOW)) {
          continue;
        }
        cleanupIncompleteArchive(eventDir, archiveAgent, agentName, "unresolved");
        if (!Files.exists(eventDir, NOFOLLOW)) {
          LOGGER
              .atInfo()
              .addKeyValue("event", "skill.archive.orphan-cleaned")
              .addKeyValue("agent", agentName)
              .addKeyValue("archiveEvent", fileName.toString())
              .log("ARCHIVE_ORPHAN_CLEANED");
        }
      }
    } catch (IOException | SecurityException error) {
      throw new PublishException(
          "ARCHIVE_CLEANUP_FAILED", "Agent archive could not be inspected safely");
    }
  }

  private static ArchiveDirectory requireExistingArchiveDirectory(
      ArchiveDirectory parent, Path child, String reasonCode) {
    Path normalized = child.toAbsolutePath().normalize();
    if (!parent.path().equals(normalized.getParent())) {
      throw new PublishException(reasonCode, "Archive directory path is invalid");
    }
    Path childReal = requireRealDirectory(normalized, reasonCode);
    if (!parent.real().equals(childReal.getParent())) {
      throw new PublishException(reasonCode, "Archive directory is outside its trusted parent");
    }
    return new ArchiveDirectory(normalized, childReal);
  }

  private void archive(ManagedSkill managed, ArchivedSkill archived) {
    byte[] metadata = archived.toYamlBytes();
    ArchiveDirectory archiveAgent = requireArchiveAgent(archived.agent());
    Path eventDir = archiveAgent.path().resolve(archived.eventDirectoryName(uuidSupplier.get()));
    if (!archiveAgent.path().equals(eventDir.getParent())) {
      throw new PublishException("UNSAFE_ARCHIVE_EVENT", "Archive event path is invalid");
    }

    boolean eventCreated = false;
    boolean complete = false;
    try {
      createArchiveEvent(eventDir);
      eventCreated = true;
      Path eventReal = requireRealDirectory(eventDir, "UNSAFE_ARCHIVE_EVENT");
      if (!archiveAgent.real().equals(eventReal.getParent())) {
        throw new PublishException(
            "UNSAFE_ARCHIVE_EVENT", "Archive event is outside the Agent archive directory");
      }

      Path metadataFile = eventDir.resolve("archive.yml");
      writeArchiveMetadata(metadataFile, metadata);
      requireRealFile(metadataFile, eventReal, "UNSAFE_ARCHIVE_EVENT");

      Path activePackage = revalidateActivePackage(managed);
      Path packageTarget = eventDir.resolve("package");
      if (!eventDir.equals(packageTarget.getParent()) || Files.exists(packageTarget, NOFOLLOW)) {
        throw new PublishException(
            "UNSAFE_ARCHIVE_EVENT", "Archive package target is already occupied");
      }

      ArchiveDirectory currentArchiveAgent = revalidateArchiveAgent(archived.agent());
      eventReal = requireRealDirectory(eventDir, "UNSAFE_ARCHIVE_EVENT");
      if (!currentArchiveAgent.real().equals(eventReal.getParent())) {
        throw new PublishException(
            "UNSAFE_ARCHIVE_EVENT", "Archive event is outside the Agent archive directory");
      }
      requireRealFile(metadataFile, eventReal, "UNSAFE_ARCHIVE_EVENT");
      if (!publishOperations.sameFileStore(activePackage, eventDir)) {
        throw new PublishException(
            "FILE_STORE_MISMATCH", "Active Skill and archive use different file stores");
      }

      try {
        publishOperations.atomicMove(activePackage, packageTarget);
      } catch (IOException | SecurityException | UnsupportedOperationException error) {
        throw new PublishException(
            "ATOMIC_ARCHIVE_FAILED", "Skill could not be archived atomically");
      }
      complete = true;
    } finally {
      if (eventCreated && !complete) {
        cleanupIncompleteArchive(eventDir, archiveAgent, archived.agent(), archived.skill());
      }
    }
  }

  private ArchiveDirectory requireArchiveAgent(String agentName) {
    Path rootReal = requireRealDirectory(oryxosRoot, "UNSAFE_WORKSPACE_ROOT");
    ArchiveDirectory root = new ArchiveDirectory(oryxosRoot, rootReal);
    ArchiveDirectory archive =
        requireOrCreateArchiveDirectory(root, "archive", "UNSAFE_ARCHIVE_ROOT");
    ArchiveDirectory skills =
        requireOrCreateArchiveDirectory(archive, ".skills", "UNSAFE_ARCHIVE_ROOT");
    return requireOrCreateArchiveDirectory(skills, agentName, "UNSAFE_ARCHIVE_AGENT");
  }

  private ArchiveDirectory revalidateArchiveAgent(String agentName) {
    Path rootReal = requireRealDirectory(oryxosRoot, "UNSAFE_WORKSPACE_ROOT");
    ArchiveDirectory root = new ArchiveDirectory(oryxosRoot, rootReal);
    ArchiveDirectory archive =
        requireExistingArchiveDirectory(root, oryxosRoot.resolve("archive"), "UNSAFE_ARCHIVE_ROOT");
    ArchiveDirectory skills =
        requireExistingArchiveDirectory(
            archive, archive.path().resolve(".skills"), "UNSAFE_ARCHIVE_ROOT");
    return requireExistingArchiveDirectory(
        skills, skills.path().resolve(agentName), "UNSAFE_ARCHIVE_AGENT");
  }

  private ArchiveDirectory requireOrCreateArchiveDirectory(
      ArchiveDirectory parent, String childName, String reasonCode) {
    Path child = parent.path().resolve(childName).normalize();
    if (!parent.path().equals(child.getParent())) {
      throw new PublishException(reasonCode, "Archive directory path is invalid");
    }
    if (!Files.exists(child, NOFOLLOW)) {
      try {
        archiveOperations.createDirectory(child);
      } catch (FileAlreadyExistsException ignored) {
        // A different Agent can create a shared archive parent concurrently. The checks below
        // decide whether that raced directory is trustworthy.
      } catch (IOException | SecurityException | UnsupportedOperationException error) {
        throw new PublishException(reasonCode, "Archive directory could not be created safely");
      }
    }
    Path childReal = requireRealDirectory(child, reasonCode);
    if (!parent.real().equals(childReal.getParent())) {
      throw new PublishException(reasonCode, "Archive directory is outside its trusted parent");
    }
    return new ArchiveDirectory(child, childReal);
  }

  private void createArchiveEvent(Path eventDir) {
    try {
      archiveOperations.createDirectory(eventDir);
    } catch (IOException | SecurityException | UnsupportedOperationException error) {
      throw new PublishException(
          "ARCHIVE_EVENT_CREATE_FAILED", "Archive event could not be created safely");
    }
  }

  private void writeArchiveMetadata(Path metadataFile, byte[] metadata) {
    try {
      archiveOperations.writeMetadata(metadataFile, metadata);
    } catch (IOException | SecurityException | UnsupportedOperationException error) {
      throw new PublishException(
          "ARCHIVE_METADATA_WRITE_FAILED", "Archive metadata could not be written safely");
    }
  }

  private Path revalidateActivePackage(ManagedSkill managed) {
    AgentName name = AgentName.parse(managed.descriptor().agentName());
    AgentPaths current = requireCurrentAgent(name);
    Path skillsDir = requireExistingSkillsDirectory(current);
    Path skillsReal = requireRealDirectory(skillsDir, "UNSAFE_SKILLS_DIRECTORY");
    Path activePackage = skillsDir.resolve(managed.descriptor().directoryName()).normalize();
    if (!skillsDir.equals(activePackage.getParent()) || !Files.exists(activePackage, NOFOLLOW)) {
      throw new PublishException(
          "ACTIVE_SKILL_CHANGED", "Active Skill package changed during archive preparation");
    }
    Path activeReal = requireRealDirectory(activePackage, "UNSAFE_SKILL_DIRECTORY");
    if (!skillsReal.equals(activeReal.getParent())) {
      throw new PublishException(
          "UNSAFE_SKILL_DIRECTORY", "Skill directory is outside the Agent skills directory");
    }
    return activePackage;
  }

  private static void requireRealFile(Path file, Path expectedParentReal, String reasonCode) {
    if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      throw new PublishException(reasonCode, "Required archive file is unavailable");
    }
    try {
      Path real = file.toRealPath();
      if (!expectedParentReal.equals(real.getParent())
          || Files.isSymbolicLink(file)
          || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
        throw new PublishException(reasonCode, "Required archive file is unavailable");
      }
    } catch (IOException | SecurityException error) {
      throw new PublishException(reasonCode, "Required archive file is unavailable");
    }
  }

  private static void cleanupIncompleteArchive(
      Path eventDir, ArchiveDirectory archiveAgent, String agentName, String skillName) {
    try {
      if (!archiveAgent.path().equals(eventDir.getParent())
          || !Files.exists(eventDir, NOFOLLOW)
          || Files.isSymbolicLink(eventDir)
          || !Files.isDirectory(eventDir, LinkOption.NOFOLLOW_LINKS)) {
        warnArchiveCleanup(agentName, skillName);
        return;
      }
      Path eventReal = eventDir.toRealPath();
      if (!archiveAgent.real().equals(eventReal.getParent())) {
        warnArchiveCleanup(agentName, skillName);
        return;
      }
      if (Files.exists(eventDir.resolve(ARCHIVE_PACKAGE_DIRECTORY), NOFOLLOW)) {
        // package/ is the completion marker. Never erase a package after a move may have committed.
        return;
      }

      Path metadataFile = eventDir.resolve("archive.yml");
      if (Files.exists(metadataFile, NOFOLLOW)) {
        if (Files.isSymbolicLink(metadataFile)
            || !Files.isRegularFile(metadataFile, LinkOption.NOFOLLOW_LINKS)) {
          warnArchiveCleanup(agentName, skillName);
          return;
        }
        Files.delete(metadataFile);
      }
      try (Stream<Path> children = Files.list(eventDir)) {
        if (children.findAny().isPresent()) {
          warnArchiveCleanup(agentName, skillName);
          return;
        }
      }
      Files.delete(eventDir);
    } catch (IOException | RuntimeException error) {
      warnArchiveCleanup(agentName, skillName);
    }
  }

  private static void warnArchiveCleanup(String agentName, String skillName) {
    LOGGER
        .atWarn()
        .addKeyValue("event", "skill.archive.cleanup-failed")
        .addKeyValue("agent", agentName)
        .addKeyValue("skill", skillName)
        .log("ARCHIVE_CLEANUP_FAILED");
  }

  private SkillDescriptor publishPrepared(AgentName name, PreparedSkill prepared) {
    AgentPaths paths = requireCurrentAgent(name);
    Path skillsDir = requireSkillsDirectory(paths);
    requirePreparedPackage(prepared);
    SkillMetadata metadata = Objects.requireNonNull(prepared.metadata(), "prepared.metadata");
    if (!metadata.name().equals(prepared.directoryName())) {
      throw new PublishException(
          "PREPARED_SKILL_INVALID", "Prepared Skill identity is inconsistent");
    }

    Path target = skillsDir.resolve(prepared.directoryName()).normalize();
    if (!skillsDir.equals(target.getParent())) {
      throw new PublishException(
          "PREPARED_SKILL_INVALID", "Prepared Skill target is not one direct child");
    }
    rejectNameConflict(skillsDir, prepared.directoryName());

    SkillMetadata activeMetadata = rebaseMetadata(metadata, target.resolve("SKILL.md"));
    catalog.validateCanImport(name.value(), activeMetadata);
    SkillDescriptor imported = importedDescriptor(name.value(), prepared, activeMetadata);

    // Repeat the path and conflict checks immediately before the FileStore/rename boundary. The
    // shared write lock excludes every supported Agent/Skill writer during this interval.
    paths = requireCurrentAgent(name);
    skillsDir = requireExistingSkillsDirectory(paths);
    target = skillsDir.resolve(prepared.directoryName()).normalize();
    rejectNameConflict(skillsDir, prepared.directoryName());
    Path packageRoot = requirePreparedPackage(prepared);
    if (!publishOperations.sameFileStore(packageRoot, skillsDir)) {
      throw new PublishException(
          "FILE_STORE_MISMATCH", "Skill staging and target use different file stores");
    }

    try {
      publishOperations.atomicMove(packageRoot, target);
    } catch (IOException | SecurityException | UnsupportedOperationException error) {
      throw new PublishException(
          "ATOMIC_PUBLISH_FAILED", "Skill could not be published atomically");
    }
    return imported;
  }

  private AgentPaths requireCurrentAgent(AgentName name) {
    Path rootReal = requireRealDirectory(oryxosRoot, "UNSAFE_WORKSPACE_ROOT");
    Path agentsReal = requireRealDirectory(agentsDir, "UNSAFE_AGENT_ROOT");
    if (!rootReal.equals(agentsReal.getParent())) {
      throw new PublishException("UNSAFE_AGENT_ROOT", "Agent root is outside the workspace");
    }
    Path agentDir = agentsDir.resolve(name.value()).normalize();
    if (!agentsDir.equals(agentDir.getParent())
        || !Files.exists(agentDir, NOFOLLOW)
        || Files.isSymbolicLink(agentDir)
        || !Files.isDirectory(agentDir, LinkOption.NOFOLLOW_LINKS)) {
      throw new NoSuchElementException("Agent does not exist: " + name.value());
    }
    Path agentReal = requireRealDirectory(agentDir, "UNSAFE_AGENT_DIRECTORY");
    if (!agentsReal.equals(agentReal.getParent())) {
      throw new PublishException(
          "UNSAFE_AGENT_DIRECTORY", "Agent directory is outside the Agent root");
    }

    Profile profile =
        profiles
            .get(name.value())
            .orElseThrow(() -> new NoSuchElementException("Agent does not exist: " + name.value()));
    try {
      name.requireFilesystemDirectoryName(agentDir);
      name.requireProfileName(profile.name());
    } catch (IllegalArgumentException error) {
      throw new PublishException(
          "AGENT_IDENTITY_MISMATCH", "Agent directory and profile identity do not match");
    }
    return new AgentPaths(agentDir, agentReal, agentDir.resolve(SKILLS_DIRECTORY));
  }

  private Path requireSkillsDirectory(AgentPaths paths) {
    if (!Files.exists(paths.skillsDir(), NOFOLLOW)) {
      try {
        Files.createDirectory(paths.skillsDir());
      } catch (IOException | SecurityException | UnsupportedOperationException error) {
        throw new PublishException(
            "SKILLS_DIRECTORY_UNAVAILABLE", "Agent skills directory could not be created");
      }
    }
    return requireExistingSkillsDirectory(paths);
  }

  private Path requireExistingSkillsDirectory(AgentPaths paths) {
    Path skillsReal = requireRealDirectory(paths.skillsDir(), "UNSAFE_SKILLS_DIRECTORY");
    if (!paths.agentReal().equals(skillsReal.getParent())) {
      throw new PublishException(
          "UNSAFE_SKILLS_DIRECTORY", "Agent skills directory is outside the Agent directory");
    }
    try {
      if (!FilesystemEntryNames.isStoredAs(paths.skillsDir(), SKILLS_DIRECTORY)) {
        throw new PublishException(
            "UNSAFE_SKILLS_DIRECTORY", "Agent skills directory uses a non-canonical name");
      }
    } catch (IOException | SecurityException error) {
      throw new PublishException(
          "UNSAFE_SKILLS_DIRECTORY", "Agent skills directory could not be inspected safely");
    }
    return paths.skillsDir();
  }

  private Path requirePreparedPackage(PreparedSkill prepared) {
    Objects.requireNonNull(prepared, "prepared");
    Path eventDir =
        Objects.requireNonNull(prepared.stagingEventDir(), "prepared.stagingEventDir")
            .toAbsolutePath()
            .normalize();
    Path packageRoot =
        Objects.requireNonNull(prepared.packageRoot(), "prepared.packageRoot")
            .toAbsolutePath()
            .normalize();
    Path rootReal = requireRealDirectory(oryxosRoot, "UNSAFE_WORKSPACE_ROOT");
    Path stagingDir = oryxosRoot.resolve(".staging").normalize();
    Path stagingReal = requireRealDirectory(stagingDir, "UNSAFE_STAGING_ROOT");
    if (!rootReal.equals(stagingReal.getParent())) {
      throw new PublishException(
          "UNSAFE_STAGING_ROOT", "Skill staging root is outside the workspace");
    }
    Path importDir = stagingDir.resolve("skill-import").normalize();
    Path importReal = requireRealDirectory(importDir, "UNSAFE_STAGING_ROOT");
    if (!stagingReal.equals(importReal.getParent())) {
      throw new PublishException(
          "UNSAFE_STAGING_ROOT", "Skill import staging root is outside staging");
    }
    if (!importDir.equals(eventDir.getParent()) || !isUuid(eventDir.getFileName())) {
      throw new PublishException(
          "PREPARED_SKILL_INVALID", "Prepared Skill event is not a trusted staging child");
    }
    Path eventReal = requireRealDirectory(eventDir, "PREPARED_SKILL_INVALID");
    if (!importReal.equals(eventReal.getParent())) {
      throw new PublishException(
          "PREPARED_SKILL_INVALID", "Prepared Skill event is outside import staging");
    }
    if (eventDir.equals(packageRoot) || !packageRoot.startsWith(eventDir)) {
      throw new PublishException(
          "PREPARED_SKILL_INVALID", "Prepared Skill is outside its staging event");
    }
    requireRealDescendantChain(eventDir, eventReal, packageRoot);
    return packageRoot;
  }

  private static void requireRealDescendantChain(
      Path ancestor, Path ancestorReal, Path descendant) {
    Path current = ancestor;
    Path currentReal = ancestorReal;
    for (Path segment : ancestor.relativize(descendant)) {
      current = current.resolve(segment);
      Path nextReal = requireRealDirectory(current, "PREPARED_SKILL_INVALID");
      if (!currentReal.equals(nextReal.getParent())) {
        throw new PublishException(
            "PREPARED_SKILL_INVALID", "Prepared Skill staging chain contains an alias");
      }
      currentReal = nextReal;
    }
  }

  private void rejectNameConflict(Path skillsDir, String directoryName) {
    String wanted = normalizedNameKey(directoryName);
    try (Stream<Path> children = Files.list(skillsDir)) {
      boolean conflict =
          children.anyMatch(
              child -> normalizedNameKey(requireFilesystemName(child)).equals(wanted));
      if (conflict) {
        throw new SkillConflictException("Skill already exists: " + directoryName);
      }
    } catch (SkillConflictException error) {
      throw error;
    } catch (IOException | SecurityException error) {
      throw new PublishException(
          "SKILL_CONFLICT_CHECK_FAILED", "Existing Skill names could not be checked safely");
    }
  }

  private static String requireFilesystemName(Path child) {
    Path fileName = child.getFileName();
    if (fileName == null) {
      throw new PublishException(
          "SKILL_CONFLICT_CHECK_FAILED", "Existing Skill name is not representable safely");
    }
    return fileName.toString();
  }

  private static SkillMetadata rebaseMetadata(SkillMetadata metadata, Path activeEntry) {
    return new SkillMetadata(
        metadata.name(),
        metadata.description(),
        metadata.license(),
        metadata.compatibility(),
        metadata.metadata(),
        metadata.allowedTools(),
        activeEntry.toAbsolutePath().normalize(),
        metadata.relativeEntry());
  }

  private static SkillDescriptor importedDescriptor(
      String agentName, PreparedSkill prepared, SkillMetadata activeMetadata) {
    SkillOrigin origin = Objects.requireNonNull(prepared.origin(), "prepared.origin");
    SkillContentValidator.ContentStats stats =
        Objects.requireNonNull(prepared.contentStats(), "prepared.contentStats");
    return new SkillDescriptor(
        agentName,
        prepared.directoryName(),
        activeMetadata,
        SkillStatus.ENABLED,
        true,
        SkillSource.UPLOAD,
        origin.importedAt(),
        null,
        activeMetadata.relativeEntry(),
        stats.resources(),
        stats.fileCount(),
        stats.totalBytes(),
        true);
  }

  private static Path requireRealDirectory(Path directory, String reasonCode) {
    if (Files.isSymbolicLink(directory)
        || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw new PublishException(reasonCode, "Required Skill directory is unavailable");
    }
    try {
      Path real = directory.toRealPath();
      if (Files.isSymbolicLink(directory)
          || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
        throw new PublishException(reasonCode, "Required Skill directory is unavailable");
      }
      return real;
    } catch (IOException | SecurityException error) {
      throw new PublishException(reasonCode, "Required Skill directory is unavailable");
    }
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "NFC plus Locale.ROOT folding is the intentional filesystem-alias key used to reject "
              + "case and normalization collisions before an atomic publish.")
  private static String normalizedNameKey(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
  }

  private static boolean isUuid(Path fileName) {
    if (fileName == null) {
      return false;
    }
    try {
      UUID.fromString(fileName.toString());
      return true;
    } catch (IllegalArgumentException error) {
      return false;
    }
  }

  interface PublishOperations {
    boolean sameFileStore(Path source, Path targetParent);

    void atomicMove(Path source, Path target) throws IOException;
  }

  interface ArchiveOperations {
    void createDirectory(Path directory) throws IOException;

    void writeMetadata(Path metadataFile, byte[] yaml) throws IOException;
  }

  private static final class NioPublishOperations implements PublishOperations {

    @Override
    public boolean sameFileStore(Path source, Path targetParent) {
      try {
        FileStore sourceStore = Files.getFileStore(source);
        FileStore targetStore = Files.getFileStore(targetParent);
        return sourceStore.equals(targetStore);
      } catch (IOException | SecurityException | UnsupportedOperationException error) {
        throw new PublishException(
            "FILE_STORE_CHECK_FAILED", "Skill file store could not be checked safely");
      }
    }

    @Override
    public void atomicMove(Path source, Path target) throws IOException {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }
  }

  private static final class NioArchiveOperations implements ArchiveOperations {

    @Override
    public void createDirectory(Path directory) throws IOException {
      Files.createDirectory(directory);
    }

    @Override
    public void writeMetadata(Path metadataFile, byte[] yaml) throws IOException {
      Files.write(metadataFile, yaml, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
  }

  /** Internal operational failure. Web adapters intentionally map it to a generic 500 response. */
  static final class PublishException extends RuntimeException {

    private final String reasonCode;

    PublishException(String reasonCode, String safeMessage) {
      super(safeMessage);
      this.reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
    }

    String reasonCode() {
      return reasonCode;
    }
  }

  private record AgentPaths(Path agentDir, Path agentReal, Path skillsDir) {}

  private record ManagedSkill(SkillDescriptor descriptor, Path skillDir) {}

  private record ArchiveDirectory(Path path, Path real) {}

  private static final class MutationAudit {

    private final String action;
    private String agent = "unresolved";
    private String skill = "unresolved";
    private String result = "failed";
    private String reasonCode = "INTERNAL_ERROR";

    private MutationAudit(String action) {
      this.action = Objects.requireNonNull(action, "action");
    }

    static MutationAudit importStarted() {
      return started("import");
    }

    static MutationAudit started(String action) {
      return new MutationAudit(action);
    }

    void agent(String value) {
      agent = value;
    }

    String agent() {
      return agent;
    }

    void skill(String value) {
      skill = value;
    }

    String skill() {
      return skill;
    }

    void success() {
      result = "success";
      reasonCode = null;
    }

    void rejected(String reason) {
      result = "rejected";
      reasonCode = stableReason(reason);
    }

    void failed(String reason) {
      result = "failed";
      reasonCode = stableReason(reason);
    }

    void log() {
      LoggingEventBuilder event =
          switch (result) {
            case "success" -> LOGGER.atInfo();
            case "rejected" -> LOGGER.atWarn();
            default -> LOGGER.atError();
          };
      event =
          event
              .addKeyValue("event", "skill.management")
              .addKeyValue("agent", agent)
              .addKeyValue("skill", skill)
              .addKeyValue("action", action)
              .addKeyValue("result", result);
      if (reasonCode != null) {
        event = event.addKeyValue("reasonCode", reasonCode);
      }
      event.log("SKILL_MANAGEMENT");
    }

    private static String stableReason(String value) {
      if (value == null || !STABLE_REASON_CODE.matcher(value).matches()) {
        return "INTERNAL_ERROR";
      }
      return value;
    }
  }
}
