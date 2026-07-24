package io.oryxos.core.skill;

import io.oryxos.core.agent.AgentName;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

/** Sole filesystem authority for Agent-to-public-Skill standard relative links. */
public final class SkillAssociationService {

  private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};

  private final Path agentsRoot;
  private final Path publicSkillsRoot;
  private final PublicSkillCatalog publicCatalog;

  public SkillAssociationService(Path workspaceRoot, PublicSkillCatalog publicCatalog) {
    Path root = Objects.requireNonNull(workspaceRoot, "workspaceRoot").toAbsolutePath().normalize();
    this.agentsRoot = root.resolve("agents");
    this.publicSkillsRoot = root.resolve("skills");
    this.publicCatalog = Objects.requireNonNull(publicCatalog, "publicCatalog");
  }

  public static Path expectedTarget(String skillName) {
    return Path.of("..", "..", "..", "skills", SkillName.parse(skillName).value());
  }

  public List<SkillAssociation> list(String agentName) {
    AgentName name = AgentName.parse(agentName);
    Path agentDir = requireAgent(name);
    Path skillsDir = agentDir.resolve("skills");
    if (!Files.exists(skillsDir, NOFOLLOW)) {
      return List.of();
    }
    requireRealDirectory(skillsDir, "Agent Skill link directory is invalid");
    List<SkillAssociation> associations = new ArrayList<>();
    try (DirectoryStream<Path> children = Files.newDirectoryStream(skillsDir)) {
      for (Path child : children) {
        if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
          continue;
        }
        String childName = String.valueOf(child.getFileName());
        if (!Files.isSymbolicLink(child) && childName.endsWith(".md")) {
          continue;
        }
        try {
          SkillName.parse(childName);
        } catch (SkillValidationException error) {
          continue;
        }
        associations.add(inspect(name.value(), childName, child));
      }
    } catch (IOException error) {
      throw new UncheckedIOException("Agent Skill links cannot be scanned", error);
    }
    return associations.stream().sorted(Comparator.comparing(SkillAssociation::skillName)).toList();
  }

  /** Always scans every real direct Agent directory; no reverse index or cache is maintained. */
  public List<String> findLinkedAgents(String skillName) {
    String skill = SkillName.parse(skillName).value();
    requireRealDirectory(agentsRoot, "Agent root is invalid");
    List<String> linked = new ArrayList<>();
    try (DirectoryStream<Path> agents = Files.newDirectoryStream(agentsRoot)) {
      for (Path agentDir : agents) {
        if (Files.isSymbolicLink(agentDir)
            || !Files.isDirectory(agentDir, LinkOption.NOFOLLOW_LINKS)) {
          continue;
        }
        String agentName = String.valueOf(agentDir.getFileName());
        try {
          AgentName.parse(agentName).requireFilesystemDirectoryName(agentDir);
        } catch (IllegalArgumentException error) {
          continue;
        }
        Path skillsDir = agentDir.resolve("skills");
        if (Files.isSymbolicLink(skillsDir)
            || !Files.isDirectory(skillsDir, LinkOption.NOFOLLOW_LINKS)) {
          continue;
        }
        Path link = skillsDir.resolve(skill);
        if (isExactStandardLink(link, skill)) {
          linked.add(agentName);
        }
      }
    } catch (IOException error) {
      throw new UncheckedIOException("Agent associations cannot be scanned", error);
    }
    return linked.stream().distinct().sorted().toList();
  }

  public SkillAssociation associate(String agentName, String skillName) {
    AgentName agent = AgentName.parse(agentName);
    PublicSkillDescriptor skill = publicCatalog.requireLoadable(skillName);
    Path agentDir = requireAgent(agent);
    Path skillsDir = ensureSkillsDirectory(agentDir);
    Path link = skillsDir.resolve(skill.name()).normalize();
    if (!skillsDir.equals(link.getParent())) {
      throw new IllegalArgumentException("Association path is outside the Agent Skill directory");
    }
    if (Files.exists(link, NOFOLLOW)) {
      if (isExactStandardLink(link, skill.name())) {
        return inspect(agent.value(), skill.name(), link);
      }
      throw new SkillConflictException("Agent Skill path is occupied by a non-standard entry");
    }

    Path temporary = skillsDir.resolve(".oryxos-link-" + UUID.randomUUID());
    try {
      Files.createSymbolicLink(temporary, expectedTarget(skill.name()));
      try {
        Files.move(temporary, link, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException error) {
        throw new IllegalStateException("Atomic association publication is unavailable", error);
      }
      return inspect(agent.value(), skill.name(), link);
    } catch (IOException error) {
      throw new UncheckedIOException("Agent Skill association could not be created", error);
    } finally {
      try {
        Files.deleteIfExists(temporary);
      } catch (IOException ignored) {
        // A uniquely named unreferenced temporary link is safe to leave for operator cleanup.
      }
    }
  }

  public SkillAssociation unlink(String agentName, String skillName) {
    AgentName agent = AgentName.parse(agentName);
    String skill = SkillName.parse(skillName).value();
    Path agentDir = requireAgent(agent);
    Path skillsDir = agentDir.resolve("skills");
    requireRealDirectory(skillsDir, "Agent Skill link directory is invalid");
    Path link = skillsDir.resolve(skill).normalize();
    if (!Files.exists(link, NOFOLLOW)) {
      throw new NoSuchElementException("Agent Skill association does not exist");
    }
    if (!isExactStandardLink(link, skill)) {
      throw new SkillConflictException("Agent Skill entry is not a standard association link");
    }
    SkillAssociation association = inspect(agent.value(), skill, link);
    try {
      Files.delete(link);
    } catch (IOException error) {
      throw new UncheckedIOException("Agent Skill association could not be removed", error);
    }
    return association;
  }

  private SkillAssociation inspect(String agentName, String skillName, Path link) {
    if (!Files.isSymbolicLink(link)) {
      return invalid(
          agentName,
          skillName,
          link,
          "",
          SkillValidationCode.INVALID_SKILL_LINK,
          "Agent Skill entry is not a symbolic link");
    }
    Path raw;
    try {
      raw = Files.readSymbolicLink(link);
    } catch (IOException error) {
      return invalid(
          agentName,
          skillName,
          link,
          "",
          SkillValidationCode.INVALID_SKILL_LINK,
          "Agent Skill link cannot be inspected");
    }
    String rawText = raw.toString();
    Path linkParent = Objects.requireNonNull(link.getParent(), "link parent");
    if (!raw.equals(expectedTarget(skillName))
        || !linkParent.resolve(raw).normalize().equals(publicSkillsRoot.resolve(skillName))) {
      return invalid(
          agentName,
          skillName,
          link,
          rawText,
          SkillValidationCode.INVALID_SKILL_LINK,
          "Agent Skill link target is not standard");
    }
    PublicSkillDescriptor descriptor;
    try {
      descriptor = publicCatalog.get(skillName);
    } catch (NoSuchElementException error) {
      return invalid(
          agentName,
          skillName,
          link,
          rawText,
          SkillValidationCode.SKILL_RESOURCE_UNAVAILABLE,
          "Linked public Skill does not exist");
    }
    if (descriptor.status() == SkillStatus.INVALID) {
      return new SkillAssociation(
          agentName,
          skillName,
          link,
          rawText,
          LinkStatus.VALID,
          SkillStatus.INVALID,
          false,
          descriptor.validationError());
    }
    return new SkillAssociation(
        agentName,
        skillName,
        link,
        rawText,
        LinkStatus.VALID,
        descriptor.status(),
        descriptor.status() == SkillStatus.ENABLED,
        null);
  }

  private static SkillAssociation invalid(
      String agentName,
      String skillName,
      Path link,
      String rawTarget,
      SkillValidationCode code,
      String message) {
    return new SkillAssociation(
        agentName,
        skillName,
        link,
        rawTarget,
        LinkStatus.INVALID,
        null,
        false,
        new SkillValidationError(code, message));
  }

  private Path requireAgent(AgentName name) {
    requireRealDirectory(agentsRoot, "Agent root is invalid");
    Path agentDir = agentsRoot.resolve(name.value()).normalize();
    if (!agentsRoot.equals(agentDir.getParent())) {
      throw new IllegalArgumentException("Agent path is outside the Agent root");
    }
    if (Files.isSymbolicLink(agentDir) || !Files.isDirectory(agentDir, LinkOption.NOFOLLOW_LINKS)) {
      throw new NoSuchElementException("Agent does not exist: " + name.value());
    }
    name.requireFilesystemDirectoryName(agentDir);
    return agentDir;
  }

  private static Path ensureSkillsDirectory(Path agentDir) {
    Path skillsDir = agentDir.resolve("skills");
    try {
      if (!Files.exists(skillsDir, NOFOLLOW)) {
        Files.createDirectory(skillsDir);
      }
    } catch (IOException error) {
      throw new UncheckedIOException("Agent Skill link directory could not be created", error);
    }
    requireRealDirectory(skillsDir, "Agent Skill link directory is invalid");
    return skillsDir;
  }

  private static boolean isExactStandardLink(Path link, String skillName) {
    if (!Files.isSymbolicLink(link)) {
      return false;
    }
    try {
      return Files.readSymbolicLink(link).equals(expectedTarget(skillName));
    } catch (IOException error) {
      return false;
    }
  }

  private static Path requireRealDirectory(Path path, String message) {
    if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalStateException(message);
    }
    try {
      return path.toRealPath();
    } catch (IOException error) {
      throw new IllegalStateException(message, error);
    }
  }
}
