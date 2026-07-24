package io.oryxos.boot;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.skill.AgentSkillCatalog;
import io.oryxos.core.skill.AgentSkillCoordinator;
import io.oryxos.core.skill.AgentSkillLockRegistry;
import io.oryxos.core.skill.PublicSkillCatalog;
import io.oryxos.core.skill.PublicSkillManagementService;
import io.oryxos.core.skill.SkillAssociationManager;
import io.oryxos.core.skill.SkillAssociationService;
import io.oryxos.core.skill.SkillContentValidator;
import io.oryxos.core.skill.SkillLimits;
import io.oryxos.core.skill.SkillManagementEventLogger;
import io.oryxos.core.skill.SkillMetadataReader;
import io.oryxos.core.skill.SkillPackageImporter;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class SkillMarketTestSupport {

  private SkillMarketTestSupport() {}

  static Market create(Path root, String... agents) throws Exception {
    Files.createDirectories(root.resolve("skills"));
    Files.createDirectories(root.resolve("archive/.skills"));
    Files.createDirectories(root.resolve(".staging/skill-import"));
    Path agentsRoot = Files.createDirectories(root.resolve("agents"));
    Map<String, Profile> profiles = new LinkedHashMap<>();
    for (String name : agents) {
      Path directory = Files.createDirectories(agentsRoot.resolve(name));
      Files.writeString(
          directory.resolve("AGENT.md"),
          "---\nname: " + name + "\nprovider:\n  name: mock\n  model: mock\n---\nAgent body\n");
      profiles.put(name, profile(name));
    }
    SkillLimits limits = SkillLimits.defaults();
    PublicSkillCatalog catalog =
        new PublicSkillCatalog(
            root, new SkillMetadataReader(), new SkillContentValidator(), limits);
    SkillAssociationService associations = new SkillAssociationService(root, catalog);
    AgentSkillCatalog agentCatalog = new AgentSkillCatalog(root, associations, catalog, limits);
    AgentSkillCoordinator coordinator =
        new AgentSkillCoordinator(
            agentsRoot, new ProfileRegistry(profiles), agentCatalog, new AgentSkillLockRegistry());
    SkillManagementEventLogger events = new SkillManagementEventLogger();
    PublicSkillManagementService management =
        new PublicSkillManagementService(
            root,
            catalog,
            associations,
            new SkillPackageImporter(root, limits),
            coordinator.graph(),
            events);
    SkillAssociationManager associationManager =
        new SkillAssociationManager(associations, coordinator.graph(), events);
    return new Market(
        root, catalog, associations, agentCatalog, coordinator, management, associationManager);
  }

  static byte[] zip(String name, String bodyMarker) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream output = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
      output.putNextEntry(new ZipEntry("SKILL.md"));
      output.write(
          ("---\nname: "
                  + name
                  + "\ndescription: Use for the boot integration test.\n---\n\n"
                  + bodyMarker
                  + "\n")
              .getBytes(StandardCharsets.UTF_8));
      output.closeEntry();
      output.putNextEntry(new ZipEntry("references/rules.md"));
      output.write("L3_RULES".getBytes(StandardCharsets.UTF_8));
      output.closeEntry();
    }
    return bytes.toByteArray();
  }

  private static Profile profile(String name) {
    return new Profile(
        name,
        null,
        null,
        new Profile.ProviderRef("mock", "mock", null),
        List.of("read_file", "shell"),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Profile.Settings.defaults());
  }

  record Market(
      Path root,
      PublicSkillCatalog catalog,
      SkillAssociationService associations,
      AgentSkillCatalog agentCatalog,
      AgentSkillCoordinator coordinator,
      PublicSkillManagementService management,
      SkillAssociationManager associationManager) {}
}
