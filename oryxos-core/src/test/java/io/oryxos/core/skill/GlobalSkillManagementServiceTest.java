package io.oryxos.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GlobalSkillManagementServiceTest {

  @TempDir Path tempDir;

  private Path root;
  private AgentSkillCatalog catalog;
  private GlobalSkillManagementService service;

  @BeforeEach
  void setUp() throws Exception {
    root = Files.createDirectory(tempDir.resolve(".oryxos"));
    Path agents = Files.createDirectory(root.resolve("agents"));
    Files.createDirectories(agents.resolve("ops").resolve("skills"));
    ProfileRegistry profiles = new ProfileRegistry(Map.of("ops", profile("ops")));
    SkillLimits limits = SkillLimits.defaults();
    SkillMetadataReader reader = new SkillMetadataReader();
    SkillContentValidator validator = new SkillContentValidator();
    SkillAssociationStore associations = new SkillAssociationStore(root);
    AgentSkillLockRegistry locks = new AgentSkillLockRegistry();
    catalog = new AgentSkillCatalog(agents, reader, validator, limits, associations);
    service =
        new GlobalSkillManagementService(
            root,
            profiles,
            catalog,
            new SkillPackageImporter(root, limits),
            reader,
            validator,
            limits,
            associations,
            locks);
  }

  @Test
  void importEditAssociateAndArchiveLifecycle() throws Exception {
    service.importSkill(zip(skillMarkdown("Initial description")), "weather.zip");
    assertTrue(Files.isRegularFile(root.resolve("skills/weather/SKILL.md")));

    service.associate("weather", "ops");
    assertEquals("weather", catalog.snapshot("ops").skills().getFirst().name());
    assertThrows(SkillConflictException.class, () -> service.delete("weather"));

    service.updateContent("weather", skillMarkdown("Updated description"));
    assertEquals("Updated description", catalog.snapshot("ops").skills().getFirst().description());

    service.dissociate("weather", "ops");
    service.delete("weather");
    assertFalse(Files.exists(root.resolve("skills/weather")));
    try (var events = Files.list(root.resolve("archive/.global-skills"))) {
      assertEquals(1, events.count());
    }
  }

  private static String skillMarkdown(String description) {
    return "---\nname: weather\ndescription: "
        + description
        + "\n---\n\nUse trusted weather data.\n";
  }

  private static ByteArrayInputStream zip(String content) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("SKILL.md"));
      zip.write(content.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return new ByteArrayInputStream(bytes.toByteArray());
  }

  private static Profile profile(String name) {
    return new Profile(
        name,
        "test",
        null,
        new Profile.ProviderRef("mock", "mock", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Profile.Settings.defaults());
  }
}
