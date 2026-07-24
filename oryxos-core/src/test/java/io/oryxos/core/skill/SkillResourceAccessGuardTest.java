package io.oryxos.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.oryxos.core.profile.Profile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillResourceAccessGuardTest {

  @TempDir Path workspace;

  private Path entry;
  private SkillSnapshot snapshot;
  private SkillResourceAccessGuard guard;

  @BeforeEach
  void setUp() throws Exception {
    Files.createDirectories(workspace.resolve("skills/weather"));
    Files.createDirectories(workspace.resolve("agents/ops/skills"));
    Files.writeString(workspace.resolve("skills/weather/SKILL.md"), "safe");
    Files.createSymbolicLink(
        workspace.resolve("agents/ops/skills/weather"), Path.of("../../../skills/weather"));
    entry = workspace.resolve("agents/ops/skills/weather/SKILL.md").toAbsolutePath();
    SkillMetadata metadata =
        new SkillMetadata(
            "weather", "Weather", null, null, Map.of(), null, entry, "skills/weather/SKILL.md");
    snapshot = new SkillSnapshot("ops", Instant.EPOCH, List.of(metadata), 100, 0);
    guard = new SkillResourceAccessGuard(workspace);
  }

  @Test
  void authorizesOnlySnapshotMembersThroughTheUnchangedStandardLink() throws Exception {
    GuardedSkillResource resource =
        guard.authorizeIfSkillPath(snapshot, "read_file", entry, profile("read_file"));

    assertEquals("weather", resource.skillName());
    assertEquals(workspace.resolve("skills/weather/SKILL.md").toRealPath(), resource.path());
  }

  @Test
  void rejectsMissingSnapshotToolPermissionAndLinkReplacement() throws Exception {
    SkillValidationException missing =
        assertThrows(
            SkillValidationException.class,
            () ->
                guard.authorizeIfSkillPath(
                    SkillSnapshot.empty("ops"), "read_file", entry, profile("read_file")));
    assertEquals(SkillValidationCode.SKILL_NOT_IN_SNAPSHOT, missing.code());

    SkillValidationException denied =
        assertThrows(
            SkillValidationException.class,
            () -> guard.authorizeIfSkillPath(snapshot, "read_file", entry, profile("shell")));
    assertEquals(SkillValidationCode.SKILL_TOOL_NOT_ALLOWED, denied.code());

    Files.delete(workspace.resolve("agents/ops/skills/weather"));
    Files.createSymbolicLink(
        workspace.resolve("agents/ops/skills/weather"), Path.of("../../skills/weather"));
    SkillValidationException changed =
        assertThrows(
            SkillValidationException.class,
            () -> guard.authorizeIfSkillPath(snapshot, "read_file", entry, profile("read_file")));
    assertEquals(SkillValidationCode.INVALID_SKILL_LINK, changed.code());
  }

  @Test
  void rejectsSymlinkResourcesInsideThePublicPackage() throws Exception {
    Path outside = Files.writeString(workspace.resolve("outside.txt"), "secret");
    Files.createSymbolicLink(workspace.resolve("skills/weather/reference.txt"), outside);

    SkillValidationException error =
        assertThrows(
            SkillValidationException.class,
            () ->
                guard.authorizeIfSkillPath(
                    snapshot,
                    "read_file",
                    workspace.resolve("agents/ops/skills/weather/reference.txt"),
                    profile("read_file")));

    assertEquals(SkillValidationCode.SKILL_RESOURCE_OUTSIDE_PACKAGE, error.code());
  }

  private static Profile profile(String tool) {
    return new Profile(
        "ops",
        null,
        new Profile.Identity("ops", "safe"),
        new Profile.ProviderRef("provider", "model", null),
        List.of(tool),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Profile.Settings.defaults());
  }
}
