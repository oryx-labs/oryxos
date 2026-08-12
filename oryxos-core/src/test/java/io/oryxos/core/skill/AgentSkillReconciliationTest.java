package io.oryxos.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentSkillReconciliationTest {

  @TempDir Path root;

  @Test
  void classifiesFiveIssuesWhileKeepingValidArchivedLinksClean() throws Exception {
    SkillWorkspaceFixture fixture = new SkillWorkspaceFixture(root);
    Path active = fixture.agent("active", "skills:\n  - report\n");
    fixture.skill("report", "报告");
    fixture.skill("other", "其它");
    Path links = Files.createDirectories(active.resolve("skills"));
    Files.createSymbolicLink(links.resolve("dangling"), Path.of("../../../skills/dangling"));
    Files.createSymbolicLink(links.resolve("escaped"), root.resolve("skills/report"));
    Files.writeString(links.resolve("ordinary"), "not-link");
    Files.createSymbolicLink(links.resolve("wrong"), Path.of("../../../skills/other"));

    Path archived = fixture.agent("archived", "");
    fixture.bind("archived", "report");
    Files.createDirectories(root.resolve("archive"));
    Files.move(archived, root.resolve("archive/archived-1"));

    List<SkillBindingIssue> issues =
        new AgentSkillBindingService(root, new SkillMetadataReader()).reconcile();
    List<SkillBindingIssue.Type> types = issues.stream().map(SkillBindingIssue::type).toList();

    assertTrue(
        types.containsAll(
            List.of(
                SkillBindingIssue.Type.DANGLING,
                SkillBindingIssue.Type.ESCAPED,
                SkillBindingIssue.Type.INVALID_TARGET,
                SkillBindingIssue.Type.NAME_MISMATCH,
                SkillBindingIssue.Type.STALE_REFERENCE)));
    assertEquals(
        0,
        issues.stream().filter(issue -> issue.agentName().equals("archived-1")).count(),
        "归档 Agent 的合法绑定不是残留");
  }
}
