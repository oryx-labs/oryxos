package io.oryxos.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentSkillBindingServiceTest {

  @TempDir Path root;

  private AgentSkillBindingService bindings;

  @BeforeEach
  void setUp() throws IOException {
    Files.createDirectories(root.resolve("agents/ops"));
    Files.writeString(root.resolve("agents/ops/AGENT.md"), "---\nname: ops\n---\nbody");
    Files.createDirectories(root.resolve("skills"));
    bindings = new AgentSkillBindingService(root, new SkillLoader(root.resolve("skills")));
  }

  private void skill(String name, String description) throws IOException {
    Path dir = root.resolve("skills").resolve(name);
    Files.createDirectories(dir);
    Files.writeString(
        dir.resolve("SKILL.md"),
        "---\nname: " + name + "\ndescription: " + description + "\n---\nBODY-" + name);
  }

  @Test
  @DisplayName("bind 创建固定相对链接且幂等；unbind 只删链接")
  void bindAndUnbind() throws IOException {
    skill("report", "报告规范");

    AgentSkillBinding first = bindings.bind("ops", "report");
    AgentSkillBinding second = bindings.bind("ops", "report");

    Path link = root.resolve("agents/ops/skills/report");
    assertTrue(Files.isSymbolicLink(link));
    assertFalse(Files.readSymbolicLink(link).isAbsolute());
    assertEquals(Path.of("../../../skills/report"), Files.readSymbolicLink(link));
    assertEquals(first, second);
    assertEquals(
        List.of("ops"),
        bindings.references("report").stream().map(SkillReference::agentName).toList());

    bindings.unbind("ops", "report");
    bindings.unbind("ops", "report");
    assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS));
    assertTrue(Files.isRegularFile(root.resolve("skills/report/SKILL.md")));
  }

  @Test
  @DisplayName("扫描分类 dangling、escaped、invalid-target、name-mismatch、stale-reference")
  void reconcileClassifiesAllIssueTypes() throws IOException {
    skill("good", "合法");
    skill("other", "另一个");
    Files.createDirectories(root.resolve("agents/ops/skills"));
    Path agentSkills = root.resolve("agents/ops/skills");
    Files.createSymbolicLink(agentSkills.resolve("dangling"), Path.of("../../../skills/dangling"));
    Files.createSymbolicLink(agentSkills.resolve("escaped"), Path.of("../../../../outside"));
    Files.writeString(agentSkills.resolve("invalid"), "not a link");
    Files.createSymbolicLink(agentSkills.resolve("wrong-name"), Path.of("../../../skills/other"));

    Files.createDirectories(root.resolve("agents/stale/skills"));
    Files.createSymbolicLink(
        root.resolve("agents/stale/skills/good"), Path.of("../../../skills/good"));

    List<SkillBindingIssue.Type> types =
        bindings.reconcile().stream().map(SkillBindingIssue::type).toList();

    assertTrue(types.contains(SkillBindingIssue.Type.DANGLING));
    assertTrue(types.contains(SkillBindingIssue.Type.ESCAPED));
    assertTrue(types.contains(SkillBindingIssue.Type.INVALID_TARGET));
    assertTrue(types.contains(SkillBindingIssue.Type.NAME_MISMATCH));
    assertTrue(types.contains(SkillBindingIssue.Type.STALE_REFERENCE));
  }

  @Test
  @DisplayName("绝对链接和普通文件不能伪装成绑定")
  void invalidBindingsNeverBecomeVisible() throws IOException {
    skill("good", "合法");
    Files.createDirectories(root.resolve("agents/ops/skills"));
    Files.createSymbolicLink(
        root.resolve("agents/ops/skills/good"), root.resolve("skills/good").toAbsolutePath());
    Files.writeString(root.resolve("agents/ops/skills/copy"), "bad");

    assertTrue(bindings.validBindings("ops").isEmpty());
    assertEquals(2, bindings.inspect("ops").issues().size());
  }

  @Test
  @DisplayName("非法 Agent/Skill 名被拒绝")
  void unsafeNamesRejected() throws IOException {
    skill("good", "合法");
    assertThrows(IllegalArgumentException.class, () -> bindings.bind("../ops", "good"));
    assertThrows(IllegalArgumentException.class, () -> bindings.bind("ops", "../good"));
  }

  @Test
  @DisplayName("replace 全量预校验，失败不改变现有绑定")
  void replacePrevalidatesBeforeMutation() throws IOException {
    skill("good", "合法");
    bindings.bind("ops", "good");

    assertThrows(
        IllegalArgumentException.class, () -> bindings.replaceBindings("ops", List.of("missing")));

    assertEquals(
        List.of("good"),
        bindings.inspect("ops").bindings().stream().map(BoundSkillDescriptor::name).toList());
  }

  @Test
  @DisplayName("归档 Agent 的固定相对链接仍被计为结构化引用")
  void archivedReferencesAreProtected() throws IOException {
    skill("report", "报告");
    bindings.bind("ops", "report");
    Files.createDirectories(root.resolve("archive"));
    Files.move(root.resolve("agents/ops"), root.resolve("archive/ops-1"));

    List<SkillReference> references = bindings.references("report");

    assertEquals(1, references.size());
    assertEquals(SkillReference.AgentState.ARCHIVED, references.get(0).state());
    assertEquals("ops", references.get(0).agentName());
  }
}
