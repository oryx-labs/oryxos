package io.oryxos.core.skill;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.AgentMarkdown;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentSkillMigrationServiceTest {

  @TempDir Path root;

  @Test
  void validLegacyFieldMigratesOnceAndPreservesOtherText() throws Exception {
    SkillWorkspaceFixture fixture = new SkillWorkspaceFixture(root);
    Path agent = fixture.agent("ops", "description: keep\nskills:\n  - report\n");
    fixture.skill("report", "报告");
    AgentSkillBindingService bindings =
        new AgentSkillBindingService(root, new SkillLoader(root.resolve("skills")));
    AgentSkillMigrationService migration = new AgentSkillMigrationService(root, bindings);

    assertTrue(migration.migrate(agent).status() == AgentSkillMigrationService.Status.MIGRATED);
    String migrated = Files.readString(agent.resolve("AGENT.md"));
    assertTrue(migrated.contains("description: keep"));
    assertFalse(migrated.contains("skills:"));
    assertTrue(Files.isSymbolicLink(agent.resolve("skills/report")));
    assertTrue(migration.migrate(agent).status() == AgentSkillMigrationService.Status.NOT_NEEDED);
  }

  @Test
  void quotedLegacyFieldMigratesWithoutResidue() throws Exception {
    SkillWorkspaceFixture fixture = new SkillWorkspaceFixture(root);
    Path agent = fixture.agent("quoted", "\"skills\":\n- report\n");
    fixture.skill("report", "报告");
    AgentSkillMigrationService migration =
        new AgentSkillMigrationService(
            root, new AgentSkillBindingService(root, new SkillMetadataReader()));

    assertEquals(AgentSkillMigrationService.Status.MIGRATED, migration.migrate(agent).status());
    String migrated = Files.readString(agent.resolve("AGENT.md"));
    assertFalse(AgentMarkdown.hasLegacySkills(migrated));
    assertTrue(Files.isSymbolicLink(agent.resolve("skills/report")));
  }

  @Test
  void invalidLegacyReferenceLeavesBytesAndLinksUntouched() throws Exception {
    SkillWorkspaceFixture fixture = new SkillWorkspaceFixture(root);
    Path agent = fixture.agent("bad", "skills:\n  - missing\n");
    byte[] original = Files.readAllBytes(agent.resolve("AGENT.md"));
    AgentSkillBindingService bindings =
        new AgentSkillBindingService(root, new SkillLoader(root.resolve("skills")));

    AgentSkillMigrationService.MigrationResult result =
        new AgentSkillMigrationService(root, bindings).migrate(agent);

    assertTrue(result.status() == AgentSkillMigrationService.Status.FAILED);
    assertArrayEquals(original, Files.readAllBytes(agent.resolve("AGENT.md")));
    assertFalse(Files.exists(agent.resolve("skills")));
  }

  @Test
  void migrationUnionsExistingLinksAndOneFailureDoesNotBlockOtherAgents() throws Exception {
    SkillWorkspaceFixture fixture = new SkillWorkspaceFixture(root);
    fixture.skill("existing", "已有");
    fixture.skill("legacy", "旧配置");
    Path good = fixture.agent("good", "skills:\n  - legacy\n");
    fixture.bind("good", "existing");
    Path bad = fixture.agent("bad", "skills:\n  - missing\n");
    byte[] badBefore = Files.readAllBytes(bad.resolve("AGENT.md"));
    AgentSkillBindingService bindings =
        new AgentSkillBindingService(root, new SkillMetadataReader());

    AgentSkillStartupReport report = new AgentSkillMigrationService(root, bindings).migrateAll();

    assertEquals(
        List.of("existing", "legacy"),
        bindings.inspect("good").bindings().stream().map(BoundSkillDescriptor::name).toList());
    assertFalse(Files.readString(good.resolve("AGENT.md")).contains("skills:"));
    assertArrayEquals(badBefore, Files.readAllBytes(bad.resolve("AGENT.md")));
    assertTrue(
        report.migrations().stream()
            .anyMatch(
                result ->
                    result.agentName().equals("bad")
                        && result.status() == AgentSkillMigrationService.Status.FAILED));
    assertTrue(
        report.issues().stream()
            .anyMatch(
                issue ->
                    issue.agentName().equals("bad")
                        && issue.type() == SkillBindingIssue.Type.STALE_REFERENCE));
  }
}
