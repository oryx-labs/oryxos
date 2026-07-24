package io.oryxos.core.skill;

import io.oryxos.core.profile.Profile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/** Revalidates every explicit L2/L3 access against the immutable request snapshot. */
public final class SkillResourceAccessGuard {

  private static final int PACKAGE_AND_MEMBER_SEGMENTS = 2;
  private static final String READ_FILE_TOOL = "read_file";
  private static final String SHELL_TOOL = "shell";

  private final Path workspaceRoot;

  public SkillResourceAccessGuard(Path workspaceRoot) {
    this.workspaceRoot =
        Objects.requireNonNull(workspaceRoot, "workspaceRoot").toAbsolutePath().normalize();
  }

  /** Returns null when the path is unrelated to an Agent Skill link. */
  public GuardedSkillResource authorizeIfSkillPath(
      SkillSnapshot snapshot, String toolName, Path requestedPath, Profile profile) {
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(profile, "profile");
    Path request = requestedPath.toAbsolutePath().normalize();
    Path agentSkills =
        workspaceRoot.resolve("agents").resolve(snapshot.agentName()).resolve("skills").normalize();
    if (!request.startsWith(agentSkills) || request.equals(agentSkills)) {
      return null;
    }
    Path relative = agentSkills.relativize(request);
    if (relative.getNameCount() < PACKAGE_AND_MEMBER_SEGMENTS) {
      throw failure(
          SkillValidationCode.SKILL_RESOURCE_UNAVAILABLE,
          "Skill resource path must include a package member");
    }
    String skillName = SkillName.parse(relative.getName(0).toString()).value();
    boolean inSnapshot =
        snapshot.skills().stream().anyMatch(skill -> skill.name().equals(skillName));
    if (!inSnapshot) {
      throw failure(
          SkillValidationCode.SKILL_NOT_IN_SNAPSHOT,
          "Skill is not available in this request snapshot");
    }
    boolean supportedTool = READ_FILE_TOOL.equals(toolName) || SHELL_TOOL.equals(toolName);
    boolean explicitlyAllowed = profile.tools().contains(toolName);
    if (!supportedTool || !explicitlyAllowed) {
      throw failure(
          SkillValidationCode.SKILL_TOOL_NOT_ALLOWED,
          "Tool is not explicitly allowed for Skill resource access");
    }

    Path link = agentSkills.resolve(skillName);
    try {
      if (!Files.isSymbolicLink(link)
          || !Files.readSymbolicLink(link)
              .equals(SkillAssociationService.expectedTarget(skillName))) {
        throw failure(
            SkillValidationCode.INVALID_SKILL_LINK,
            "Agent Skill link changed after the request snapshot was created");
      }
      Path packageRoot = workspaceRoot.resolve("skills").resolve(skillName).normalize();
      if (Files.isSymbolicLink(packageRoot)
          || !Files.isDirectory(packageRoot, LinkOption.NOFOLLOW_LINKS)) {
        throw failure(
            SkillValidationCode.SKILL_RESOURCE_UNAVAILABLE, "Public Skill package is unavailable");
      }
      Path packageReal = packageRoot.toRealPath();
      Path member = packageRoot.resolve(relative.subpath(1, relative.getNameCount())).normalize();
      if (!member.startsWith(packageRoot)) {
        throw failure(
            SkillValidationCode.SKILL_RESOURCE_OUTSIDE_PACKAGE,
            "Skill resource resolves outside its package");
      }
      rejectLinksOnPath(packageRoot, member);
      if (!Files.exists(member, LinkOption.NOFOLLOW_LINKS)) {
        throw failure(
            SkillValidationCode.SKILL_RESOURCE_UNAVAILABLE, "Skill resource is unavailable");
      }
      Path memberReal = member.toRealPath();
      if (!memberReal.startsWith(packageReal)) {
        throw failure(
            SkillValidationCode.SKILL_RESOURCE_OUTSIDE_PACKAGE,
            "Skill resource resolves outside its package");
      }
      return new GuardedSkillResource(skillName, memberReal);
    } catch (SkillValidationException error) {
      throw error;
    } catch (IOException error) {
      throw failure(
          SkillValidationCode.SKILL_RESOURCE_UNAVAILABLE,
          "Skill resource cannot be inspected safely");
    }
  }

  private static void rejectLinksOnPath(Path packageRoot, Path member) {
    Path current = packageRoot;
    Path relative = packageRoot.relativize(member);
    for (Path segment : relative) {
      current = current.resolve(segment);
      if (Files.isSymbolicLink(current)) {
        throw failure(
            SkillValidationCode.SKILL_RESOURCE_OUTSIDE_PACKAGE,
            "Skill resources must not contain symbolic links");
      }
    }
  }

  private static SkillValidationException failure(SkillValidationCode code, String message) {
    return new SkillValidationException(code, message);
  }
}
