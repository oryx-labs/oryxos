package io.oryxos.core.fs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceMutationGuardTest {

  @TempDir Path temp;

  @Test
  @DisplayName("拒绝共享 skills/knowledge 与 Agent 绑定视图下的内容写")
  void rejectSkillKnowledgeContentWrite() {
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkspaceMutationGuard.rejectSkillKnowledgeContentWrite("skills/report/SKILL.md"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            WorkspaceMutationGuard.rejectSkillKnowledgeContentWrite(
                "agents/demo/skills/report/SKILL.md"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            WorkspaceMutationGuard.rejectSkillKnowledgeContentWrite(
                "agents/demo/Skills/report/x.md"));
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkspaceMutationGuard.rejectSkillKnowledgeContentWrite("knowledge/ops/doc.md"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            WorkspaceMutationGuard.rejectSkillKnowledgeContentWrite(
                "agents/demo/knowledge/ops/doc.md"));
    assertDoesNotThrow(
        () -> WorkspaceMutationGuard.rejectSkillKnowledgeContentWrite("agents/demo/notes.md"));
    assertDoesNotThrow(
        () -> WorkspaceMutationGuard.rejectSkillKnowledgeContentWrite("output/report.md"));
    assertDoesNotThrow(() -> WorkspaceMutationGuard.rejectSkillKnowledgeContentWrite((Path) null));
  }

  @Test
  @DisplayName("软链叶子指向 skills 内容时拒绝")
  void rejectsSymlinkLeafToSkillContent() throws IOException {
    Path skillFile = temp.resolve("skills").resolve("report").resolve("SKILL.md");
    Files.createDirectories(skillFile.getParent());
    Files.writeString(skillFile, "---\nname: report\n---\n");
    Path alias = temp.resolve("notes.md");
    assumeCanSymlink(alias, skillFile);

    assertThrows(
        IllegalArgumentException.class,
        () -> WorkspaceMutationGuard.rejectSkillKnowledgeContentWrite(alias));
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkspaceMutationGuard.rejectSkillKnowledgeContentWrite(alias.toString()));
  }

  @Test
  @DisplayName("悬空软链目标含 skills 段时也拒绝")
  void rejectsDanglingSymlinkIntoSkills() throws IOException {
    Path alias = temp.resolve("notes.md");
    assumeCanSymlink(alias, Path.of("skills/report/SKILL.md"));

    assertThrows(
        IllegalArgumentException.class,
        () -> WorkspaceMutationGuard.rejectSkillKnowledgeContentWrite(alias));
  }

  @Test
  @DisplayName("拒绝直写 agents/<name>/AGENT.md")
  void rejectAgentMdDirectWrite() {
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkspaceMutationGuard.rejectAgentMdDirectWrite("agents/demo/AGENT.md"));
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkspaceMutationGuard.rejectAgentMdDirectWrite("agents/demo/agent.md"));
    assertDoesNotThrow(
        () -> WorkspaceMutationGuard.rejectAgentMdDirectWrite("agents/demo/notes.md"));
    assertDoesNotThrow(
        () -> WorkspaceMutationGuard.rejectAgentMdDirectWrite("agents/demo/skills/AGENT.md"));
    assertDoesNotThrow(() -> WorkspaceMutationGuard.rejectAgentMdDirectWrite((Path) null));
  }

  @Test
  @DisplayName("软链叶子指向 AGENT.md 时拒绝")
  void rejectsSymlinkLeafToAgentMd() throws IOException {
    Path agentDir = temp.resolve("agents").resolve("demo");
    Files.createDirectories(agentDir);
    Path agentMd = agentDir.resolve("AGENT.md");
    Files.writeString(agentMd, "name: demo\n");
    Path alias = agentDir.resolve("notes.md");
    assumeCanSymlink(alias, agentMd);

    assertThrows(
        IllegalArgumentException.class,
        () -> WorkspaceMutationGuard.rejectAgentMdDirectWrite(alias));
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkspaceMutationGuard.rejectAgentMdDirectWrite(alias.toString()));
  }

  @Test
  @DisplayName("悬空软链目标名为 AGENT.md 时也拒绝")
  void rejectsDanglingSymlinkNamedAgentMd() throws IOException {
    Path alias = temp.resolve("notes.md");
    assumeCanSymlink(alias, Path.of("AGENT.md"));

    assertThrows(
        IllegalArgumentException.class,
        () -> WorkspaceMutationGuard.rejectAgentMdDirectWrite(alias));
  }

  private static void assumeCanSymlink(Path link, Path target) {
    try {
      Files.createSymbolicLink(link, target);
    } catch (IOException | UnsupportedOperationException e) {
      assumeTrue(false, "当前环境无法创建软链: " + e.getMessage());
    }
  }

  @Test
  @DisplayName("拒绝 make_dir 占用 bind 叶子；允许建 skills 目录本身")
  void rejectBindSlotCreate() {
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkspaceMutationGuard.rejectBindSlotCreate("agents/demo/skills/report"));
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkspaceMutationGuard.rejectBindSlotCreate("agents/demo/knowledge/ops"));
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkspaceMutationGuard.rejectBindSlotCreate("agents/demo/skills/report/sub"));
    assertDoesNotThrow(() -> WorkspaceMutationGuard.rejectBindSlotCreate("agents/demo/skills"));
    assertDoesNotThrow(() -> WorkspaceMutationGuard.rejectBindSlotCreate("agents/demo/output"));
  }

  @Test
  @DisplayName("拒绝 delete/move 拆 bind 叶子")
  void rejectBindLinkDetach() {
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkspaceMutationGuard.rejectBindLinkDetach("agents/demo/skills/report"));
    assertDoesNotThrow(
        () -> WorkspaceMutationGuard.rejectBindLinkDetach("agents/demo/skills/report/SKILL.md"));
  }
}
