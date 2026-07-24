package io.oryxos.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillAssociationServiceTest {

  @TempDir Path parent;

  private Path workspace;
  private SkillAssociationService associations;

  @BeforeEach
  void setUp() throws Exception {
    workspace = Files.createDirectory(parent.resolve(".oryxos"));
    Files.createDirectories(workspace.resolve("agents/ops/skills"));
    Files.createDirectories(workspace.resolve("agents/other/skills"));
    Files.createDirectories(workspace.resolve("skills"));
    Path skill = Files.createDirectory(workspace.resolve("skills/weather"));
    Files.write(skill.resolve("SKILL.md"), SkillPackageTestSupport.validSkillMarkdown("weather"));
    PublicSkillCatalog catalog =
        new PublicSkillCatalog(
            workspace,
            new SkillMetadataReader(),
            new SkillContentValidator(),
            SkillLimits.defaults());
    associations = new SkillAssociationService(workspace, catalog);
  }

  @Test
  void createsExactRelativeLinkAndIsIdempotent() throws Exception {
    SkillAssociation first = associations.associate("ops", "weather");
    SkillAssociation second = associations.associate("ops", "weather");
    Path link = workspace.resolve("agents/ops/skills/weather");

    assertEquals(Path.of("../../../skills/weather"), Files.readSymbolicLink(link));
    assertEquals(LinkStatus.VALID, first.linkStatus());
    assertEquals(first, second);
    assertEquals(List.of("ops"), associations.findLinkedAgents("weather"));
    assertTrue(associations.unlink("ops", "weather").discoverable());
    assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS));
    assertTrue(Files.exists(workspace.resolve("skills/weather/SKILL.md")));
  }

  @Test
  void neverOverwritesOrCountsNonStandardEntries() throws Exception {
    Path occupied = workspace.resolve("agents/ops/skills/weather");
    Files.createSymbolicLink(occupied, Path.of("../../skills/weather"));

    assertThrows(SkillConflictException.class, () -> associations.associate("ops", "weather"));
    assertThrows(SkillConflictException.class, () -> associations.unlink("ops", "weather"));
    assertEquals(List.of(), associations.findLinkedAgents("weather"));
    assertEquals(LinkStatus.INVALID, associations.list("ops").get(0).linkStatus());
  }

  @Test
  void relativeLinkSurvivesMovingTheWholeWorkspace() throws Exception {
    associations.associate("ops", "weather");
    Path moved = parent.resolve("moved-workspace");
    Files.move(workspace, moved);

    assertEquals(
        Path.of("../../../skills/weather"),
        Files.readSymbolicLink(moved.resolve("agents/ops/skills/weather")));
    assertTrue(Files.isRegularFile(moved.resolve("agents/ops/skills/weather/SKILL.md")));
  }
}
