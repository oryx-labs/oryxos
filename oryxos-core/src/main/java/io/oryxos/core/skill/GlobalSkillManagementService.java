package io.oryxos.core.skill;

import io.oryxos.core.agent.AgentName;
import io.oryxos.core.profile.ProfileRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** CRUD and Agent association service for workspace-wide public Skills. */
public final class GlobalSkillManagementService {

  private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};

  private final Path oryxosRoot;
  private final Path agentsDir;
  private final Path skillsDir;
  private final ProfileRegistry profiles;
  private final AgentSkillCatalog catalog;
  private final SkillPackageImporter importer;
  private final SkillMetadataReader metadataReader;
  private final SkillContentValidator contentValidator;
  private final SkillLimits limits;
  private final SkillAssociationStore associations;
  private final AgentSkillLockRegistry locks;
  private final Object mutationLock = new Object();

  public GlobalSkillManagementService(
      Path oryxosRoot,
      ProfileRegistry profiles,
      AgentSkillCatalog catalog,
      SkillPackageImporter importer,
      SkillMetadataReader metadataReader,
      SkillContentValidator contentValidator,
      SkillLimits limits,
      SkillAssociationStore associations,
      AgentSkillLockRegistry locks) {
    this.oryxosRoot = Objects.requireNonNull(oryxosRoot, "oryxosRoot").toAbsolutePath().normalize();
    this.agentsDir = this.oryxosRoot.resolve("agents");
    this.skillsDir = this.oryxosRoot.resolve("skills");
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.importer = Objects.requireNonNull(importer, "importer");
    this.metadataReader = Objects.requireNonNull(metadataReader, "metadataReader");
    this.contentValidator = Objects.requireNonNull(contentValidator, "contentValidator");
    this.limits = Objects.requireNonNull(limits, "limits");
    this.associations = Objects.requireNonNull(associations, "associations");
    this.locks = Objects.requireNonNull(locks, "locks");
  }

  public List<SkillDescriptor> list() {
    return catalog.listGlobal();
  }

  public SkillDescriptor get(String skillName) {
    return catalog.getGlobal(requireSkillName(skillName));
  }

  public String getContent(String skillName) {
    Path entry = requirePackage(skillName).resolve("SKILL.md");
    try {
      if (Files.isSymbolicLink(entry)
          || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
          || Files.size(entry) > limits.maxSkillMarkdownBytes()) {
        throw new IllegalStateException("Public Skill entry is unavailable");
      }
      return Files.readString(entry, StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new IllegalStateException("Public Skill entry could not be read", error);
    }
  }

  public List<String> associatedAgents(String skillName) {
    return associations.agentsFor(skillName);
  }

  public List<String> availableAgents() {
    return profiles.all().stream().map(profile -> profile.name()).sorted().toList();
  }

  public SkillDescriptor importSkill(InputStream zip, String originalFilename) {
    if (zip == null) {
      throw new IllegalArgumentException("Skill upload must not be null");
    }
    PreparedSkill prepared = importer.prepare(zip, originalFilename);
    try {
      synchronized (mutationLock) {
        ensureLibrary();
        if (catalog.listGlobal().size() >= limits.maxCandidatesPerAgent()) {
          throw new SkillValidationException(
              SkillValidationCode.TOO_MANY_SKILLS, "Public Skill library has reached its limit");
        }
        Path target = skillsDir.resolve(prepared.directoryName()).normalize();
        if (!skillsDir.equals(target.getParent()) || Files.exists(target, NOFOLLOW)) {
          throw new SkillConflictException(
              "Public Skill already exists: " + prepared.directoryName());
        }
        try {
          FileStore sourceStore = Files.getFileStore(prepared.packageRoot());
          FileStore targetStore = Files.getFileStore(skillsDir);
          if (!sourceStore.equals(targetStore)) {
            throw new IllegalStateException(
                "Skill staging and public library use different file stores");
          }
          Files.move(prepared.packageRoot(), target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | UnsupportedOperationException error) {
          throw new IllegalStateException("Public Skill could not be published atomically", error);
        }
        return catalog.getGlobal(prepared.directoryName());
      }
    } finally {
      try {
        importer.discard(prepared);
      } catch (RuntimeException ignored) {
        // Publication is atomic; cleanup failure must not turn a visible Skill into a failed
        // import.
      }
    }
  }

  public SkillDescriptor updateContent(String skillName, String markdown) {
    String skill = requireSkillName(skillName);
    if (markdown == null || markdown.isBlank()) {
      throw new IllegalArgumentException("SKILL.md content must not be blank");
    }
    byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > limits.maxSkillMarkdownBytes()) {
      throw new SkillValidationException(
          SkillValidationCode.SKILL_MARKDOWN_TOO_LARGE, "SKILL.md exceeds the size limit");
    }
    synchronized (mutationLock) {
      List<String> agents = associations.agentsFor(skill);
      return withAgentWriteLocks(
          agents,
          0,
          () -> {
            Path packageDir = requirePackage(skill);
            SkillContentValidator.ContentStats current =
                contentValidator.validate(packageDir, limits);
            Path entry = packageDir.resolve("SKILL.md");
            try {
              long nextTotal = current.totalBytes() - Files.size(entry) + bytes.length;
              if (nextTotal > limits.maxExpandedBytes()) {
                throw new SkillValidationException(
                    SkillValidationCode.PACKAGE_TOO_LARGE, "Skill content exceeds the size limit");
              }
            } catch (IOException error) {
              throw new IllegalStateException("Public Skill entry could not be inspected", error);
            }

            Path editRoot = createEditRoot(skill, markdown);
            Path stagedEntry = editRoot.resolve("skills").resolve(skill).resolve("SKILL.md");
            try {
              metadataReader.read(editRoot, stagedEntry.getParent(), limits);
              if (!Files.getFileStore(stagedEntry).equals(Files.getFileStore(packageDir))) {
                throw new IllegalStateException("Skill edit staging uses a different file store");
              }
              Files.move(
                  stagedEntry,
                  entry,
                  StandardCopyOption.ATOMIC_MOVE,
                  StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | UnsupportedOperationException error) {
              throw new IllegalStateException(
                  "Public Skill content could not be saved atomically", error);
            } finally {
              deleteTree(editRoot);
            }
            return catalog.getGlobal(skill);
          });
    }
  }

  public void delete(String skillName) {
    String skill = requireSkillName(skillName);
    synchronized (mutationLock) {
      List<String> agents = associations.agentsFor(skill);
      if (!agents.isEmpty()) {
        throw new SkillConflictException(
            "Public Skill is still associated with Agents: " + String.join(", ", agents));
      }
      Path source = requirePackage(skill);
      Path archive = oryxosRoot.resolve("archive").resolve(".global-skills");
      try {
        Files.createDirectories(archive);
        Path target =
            archive.resolve(Instant.now().toEpochMilli() + "-" + UUID.randomUUID()).normalize();
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException | UnsupportedOperationException error) {
        throw new IllegalStateException("Public Skill could not be archived atomically", error);
      }
    }
  }

  public void associate(String skillName, String agentName) {
    String skill = requireSkillName(skillName);
    String agent = requireAgent(agentName);
    synchronized (mutationLock) {
      locks.withWriteLock(
          agent,
          () -> {
            SkillDescriptor publicSkill = catalog.getGlobal(skill);
            if (publicSkill.status() != SkillStatus.ENABLED) {
              throw new SkillValidationException(
                  SkillValidationCode.CONTENT_UNREADABLE,
                  "Only a valid enabled public Skill can be associated");
            }
            boolean localConflict =
                catalog.list(agent).stream()
                    .anyMatch(descriptor -> descriptor.directoryName().equals(skill));
            if (localConflict) {
              throw new SkillConflictException(
                  "Agent already has a private Skill with the same name: " + skill);
            }
            associations.associate(skill, agent);
            boolean included =
                catalog.snapshot(agent).skills().stream()
                    .anyMatch(metadata -> metadata.name().equals(skill));
            if (!included) {
              associations.dissociate(skill, agent);
              throw new SkillValidationException(
                  SkillValidationCode.CATALOG_BUDGET_EXCEEDED,
                  "Agent Skill catalog exceeds the metadata budget");
            }
            return null;
          });
    }
  }

  public void dissociate(String skillName, String agentName) {
    String skill = requireSkillName(skillName);
    String agent = requireAgent(agentName);
    synchronized (mutationLock) {
      locks.withWriteLock(
          agent,
          () -> {
            catalog.getGlobal(skill);
            associations.dissociate(skill, agent);
            return null;
          });
    }
  }

  private <T> T withAgentWriteLocks(List<String> agents, int index, Supplier<T> operation) {
    if (index >= agents.size()) {
      return operation.get();
    }
    return locks.withWriteLock(
        agents.get(index), () -> withAgentWriteLocks(agents, index + 1, operation));
  }

  private String requireAgent(String value) {
    String agent = AgentName.parse(value).value();
    if (!profiles.exists(agent)) {
      throw new NoSuchElementException("Agent does not exist: " + agent);
    }
    Path agentDir = agentsDir.resolve(agent).normalize();
    if (!agentsDir.equals(agentDir.getParent())
        || Files.isSymbolicLink(agentDir)
        || !Files.isDirectory(agentDir, LinkOption.NOFOLLOW_LINKS)) {
      throw new NoSuchElementException("Agent does not exist: " + agent);
    }
    return agent;
  }

  private Path requirePackage(String skillName) {
    String skill = requireSkillName(skillName);
    Path packageDir = skillsDir.resolve(skill).normalize();
    if (!skillsDir.equals(packageDir.getParent())
        || Files.isSymbolicLink(packageDir)
        || !Files.isDirectory(packageDir, LinkOption.NOFOLLOW_LINKS)) {
      throw new NoSuchElementException("Public Skill does not exist: " + skill);
    }
    return packageDir;
  }

  private void ensureLibrary() {
    try {
      Files.createDirectories(oryxosRoot);
      Files.createDirectories(skillsDir);
      if (Files.isSymbolicLink(oryxosRoot)
          || Files.isSymbolicLink(skillsDir)
          || !Files.isDirectory(skillsDir, LinkOption.NOFOLLOW_LINKS)
          || !oryxosRoot.toRealPath().equals(skillsDir.toRealPath().getParent())) {
        throw new IllegalStateException("Public Skill library is unavailable");
      }
    } catch (IOException error) {
      throw new IllegalStateException("Public Skill library is unavailable", error);
    }
  }

  private Path createEditRoot(String skill, String markdown) {
    try {
      Path staging = oryxosRoot.resolve(".staging");
      Files.createDirectories(staging);
      Path root = Files.createTempDirectory(staging, "skill-edit-");
      Path packageDir = root.resolve("skills").resolve(skill);
      Files.createDirectories(packageDir);
      Files.writeString(packageDir.resolve("SKILL.md"), markdown, StandardCharsets.UTF_8);
      return root;
    } catch (IOException error) {
      throw new IllegalStateException("Skill edit staging could not be created", error);
    }
  }

  private static void deleteTree(Path root) {
    if (root == null || !Files.exists(root, NOFOLLOW)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    } catch (IOException ignored) {
      // A committed atomic edit remains successful; startup/operator cleanup can remove leftovers.
    }
  }

  private static String requireSkillName(String value) {
    if (!SkillMetadata.isValidName(value)) {
      throw new IllegalArgumentException("Invalid Skill name: " + value);
    }
    return value;
  }
}
