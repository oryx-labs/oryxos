package io.oryxos.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GlobalSkillCatalogTest {

  @TempDir Path tempDir;

  @Test
  void assignedPublicSkillIsMergedOnlyIntoTheRuntimeSnapshot() throws Exception {
    Path root = tempDir.resolve(".oryxos");
    Path agents = Files.createDirectories(root.resolve("agents"));
    Files.createDirectories(agents.resolve("demo").resolve("skills"));
    Path skill = Files.createDirectories(root.resolve("skills").resolve("weather"));
    Files.writeString(
        skill.resolve("SKILL.md"),
        "---\nname: weather\ndescription: Weather guidance\n---\n\nUse trusted weather data.\n");
    SkillAssociationStore associations = new SkillAssociationStore(root);
    associations.associate("weather", "demo");
    AgentSkillCatalog catalog =
        new AgentSkillCatalog(
            agents,
            new SkillMetadataReader(),
            new SkillContentValidator(),
            SkillLimits.defaults(),
            associations);

    assertTrue(catalog.list("demo").isEmpty());
    assertEquals("weather", catalog.listGlobal().getFirst().metadata().name());
    assertEquals("weather", catalog.snapshot("demo").skills().getFirst().name());
    assertEquals(
        skill.resolve("SKILL.md").toAbsolutePath(),
        catalog.snapshot("demo").skills().getFirst().entryPath());
  }
}
