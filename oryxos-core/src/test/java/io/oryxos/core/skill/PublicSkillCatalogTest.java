package io.oryxos.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublicSkillCatalogTest {

  @TempDir Path workspace;

  private PublicSkillCatalog catalog;

  @BeforeEach
  void setUp() throws Exception {
    Files.createDirectories(workspace.resolve("skills"));
    catalog =
        new PublicSkillCatalog(
            workspace,
            new SkillMetadataReader(),
            new SkillContentValidator(),
            SkillLimits.defaults());
  }

  @Test
  void isolatesInvalidCandidatesAndDerivesGlobalStateFresh() throws Exception {
    Path enabled = skill("enabled");
    Path disabled = skill("disabled");
    Files.createFile(disabled.resolve(".oryxos-disabled"));
    Path invalid = Files.createDirectory(workspace.resolve("skills/invalid"));
    Files.writeString(invalid.resolve("SKILL.md"), "not frontmatter");
    Files.createDirectory(workspace.resolve("skills/legacy-directory"));

    List<PublicSkillDescriptor> descriptors = catalog.list();

    assertEquals(
        List.of("disabled", "enabled", "invalid"),
        descriptors.stream().map(PublicSkillDescriptor::name).toList());
    assertEquals(SkillStatus.DISABLED, catalog.get("disabled").status());
    assertEquals(SkillStatus.ENABLED, catalog.get("enabled").status());
    assertEquals(SkillStatus.INVALID, catalog.get("invalid").status());
    assertFalse(catalog.get("enabled").toString().contains(workspace.toString()));

    Files.createFile(enabled.resolve(".oryxos-disabled"));
    assertEquals(SkillStatus.DISABLED, catalog.get("enabled").status());
  }

  private Path skill(String name) throws Exception {
    Path dir = Files.createDirectory(workspace.resolve("skills").resolve(name));
    Files.write(dir.resolve("SKILL.md"), SkillPackageTestSupport.validSkillMarkdown(name));
    return dir;
  }
}
