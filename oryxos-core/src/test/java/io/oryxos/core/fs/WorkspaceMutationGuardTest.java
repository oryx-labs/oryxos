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
  @DisplayName("经别名目录建目录投影到绑定槽时拒绝（父目录换链 TOCTOU）")
  void rejectsMkdirViaSymlinkedAliasIntoBindSlot() throws IOException {
    Files.createDirectories(temp.resolve("agents").resolve("demo").resolve("skills"));
    Files.createDirectories(temp.resolve("output"));
    Path linkedDir = temp.resolve("output").resolve("skills");
    assumeCanSymlink(linkedDir, Path.of("../agents/demo/skills"));

    assertThrows(
        IllegalArgumentException.class,
        () -> WorkspaceMutationGuard.rejectBindSlotCreate(linkedDir.resolve("report").toString()));
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkspaceMutationGuard.rejectBindSlotCreate(linkedDir.resolve("report")));
    // 别名只投影到 skills 父目录本身时仍放行（与词法口径一致：只禁占叶子槽）
    assertDoesNotThrow(() -> WorkspaceMutationGuard.rejectBindSlotCreate(linkedDir));
  }

  @Test
  @DisplayName("悬空软链叶子目标词法命中绑定树时也拒绝")
  void rejectsDanglingSymlinkLeafIntoBindSlot() throws IOException {
    Files.createDirectories(temp.resolve("output"));
    Path link = temp.resolve("output").resolve("slot");
    assumeCanSymlink(link, Path.of("../agents/demo/skills/report"));

    assertThrows(
        IllegalArgumentException.class,
        () -> WorkspaceMutationGuard.rejectBindSlotCreate(link.toString()));
    assertThrows(
        IllegalArgumentException.class, () -> WorkspaceMutationGuard.rejectBindSlotCreate(link));
  }

  @Test
  @DisplayName("指向普通文件/目录的软链放行（不误伤绑定槽守卫）")
  void allowsOrdinarySymlinkTargetsForBindSlot() throws IOException {
    Files.writeString(temp.resolve("notes.md"), "notes\n");
    Path fileAlias = temp.resolve("alias.md");
    assumeCanSymlink(fileAlias, Path.of("notes.md"));
    Files.createDirectories(temp.resolve("plaindir"));
    Files.createDirectories(temp.resolve("output"));
    Path dirAlias = temp.resolve("output").resolve("plain");
    assumeCanSymlink(dirAlias, Path.of("../plaindir"));

    assertDoesNotThrow(() -> WorkspaceMutationGuard.rejectBindSlotCreate(fileAlias));
    assertDoesNotThrow(() -> WorkspaceMutationGuard.rejectBindSlotCreate(fileAlias.toString()));
    assertDoesNotThrow(() -> WorkspaceMutationGuard.rejectBindSlotCreate(dirAlias.resolve("sub")));
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

  @Test
  @DisplayName("AGENT.md 槽位被建成目录（路径继续往下）同样拒绝")
  void rejectsAgentMdSlotUsedAsDirectory() {
    // agents/<name>/AGENT.md 是 Agent 自身的配置文件槽位。路径在它之后继续延伸时，
    // 调用方（write_file / make_dir）会 createDirectories 把它建成【目录】，
    // 之后 AgentStore.write 会以「Agent 文件目标不是普通文件」失败。
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkspaceMutationGuard.rejectAgentMdDirectWrite("agents/demo/AGENT.md/x.txt"));
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkspaceMutationGuard.rejectAgentMdDirectWrite("agents/demo/agent.md/sub"));
    // 末段形式仍拒绝（既有行为）
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkspaceMutationGuard.rejectAgentMdDirectWrite("agents/demo/AGENT.md"));
    // 共享 Skill 自己的 AGENT.md 仍放行（既有行为，不在 Agent 槽位上）
    assertDoesNotThrow(
        () -> WorkspaceMutationGuard.rejectAgentMdDirectWrite("agents/demo/skills/AGENT.md"));
  }
}
