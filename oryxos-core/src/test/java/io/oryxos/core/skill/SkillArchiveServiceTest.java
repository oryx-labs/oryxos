package io.oryxos.core.skill;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillArchiveServiceTest {

  @TempDir Path root;

  @Test
  void referencesBlockArchiveThenCompleteDirectoryIsMoved() throws Exception {
    SkillWorkspaceFixture fixture = new SkillWorkspaceFixture(root);
    fixture.agent("ops", "");
    fixture.skill("report", "报告");
    Files.writeString(root.resolve("skills/report/REFERENCE.md"), "keep-me");
    fixture.bind("ops", "report");
    SkillLoader loader = new SkillLoader(root.resolve("skills"));
    SkillRegistry registry = loader.loadAll();
    AgentSkillBindingService bindings = new AgentSkillBindingService(root, loader);
    SkillService service = new SkillService(new SkillStore(root), registry, loader, bindings);

    assertThrows(SkillReferencedException.class, () -> service.delete("report"));
    bindings.unbind("ops", "report");

    SkillArchive archive = service.delete("report");
    assertFalse(Files.exists(root.resolve("skills/report")));
    assertTrue(Files.isRegularFile(root.resolve(archive.archivedPath()).resolve("REFERENCE.md")));
  }

  @Test
  void repeatedArchivesNeverOverwriteAndLegacyAgentNamedSkillsIsPreserved() throws Exception {
    Path legacy = Files.createDirectories(root.resolve("archive/skills"));
    Files.writeString(legacy.resolve("AGENT.md"), "---\nname: skills\n---\nlegacy");
    Clock fixed = Clock.fixed(Instant.parse("2026-07-27T01:02:03Z"), ZoneOffset.UTC);
    SkillStore store = new SkillStore(root, fixed);
    store.write("report", "---\nname: report\ndescription: d\n---\nfirst");
    SkillArchive first = store.archive("report");
    store.write("report", "---\nname: report\ndescription: d\n---\nsecond");
    SkillArchive second = store.archive("report");

    assertTrue(Files.isRegularFile(root.resolve(first.archivedPath()).resolve("SKILL.md")));
    assertTrue(Files.isRegularFile(root.resolve(second.archivedPath()).resolve("SKILL.md")));
    assertFalse(first.archivedPath().equals(second.archivedPath()));
    try (java.util.stream.Stream<Path> archives = Files.list(root.resolve("archive"))) {
      assertTrue(
          archives.anyMatch(
              path ->
                  path.getFileName().toString().startsWith("skills-")
                      && Files.isRegularFile(path.resolve("AGENT.md"))));
    }
  }
}
