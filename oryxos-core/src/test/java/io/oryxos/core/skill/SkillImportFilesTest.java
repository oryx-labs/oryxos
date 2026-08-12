package io.oryxos.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 32 节验收：从 GitHub 目录导入——整个文件夹（SKILL.md + 附带文件）原样落盘，不只是单文件重组。 */
class SkillImportFilesTest {

  @TempDir Path oryxosRoot;

  private SkillStore store;
  private SkillRegistry registry;
  private SkillService service;

  @BeforeEach
  void setUp() {
    store = new SkillStore(oryxosRoot);
    SkillLoader loader = new SkillLoader(oryxosRoot.resolve("skills"));
    registry = loader.loadAll();
    service = new SkillService(store, registry, loader);
  }

  @Test
  @DisplayName("importFiles：SKILL.md 解析出 name/description，附带文件原样落盘")
  void importFiles_writesAllFilesAndRegistersFromSkillMd() {
    Map<String, String> files =
        Map.of(
            "SKILL.md", "---\nname: brainstorming\ndescription: 头脑风暴\n---\n\n正文指令",
            "scripts/run.py", "print('hi')",
            "REFERENCE.md", "参考资料");

    Skill s = service.importFiles(null, files, "fallback");

    assertEquals("brainstorming", s.name());
    assertEquals("头脑风暴", s.description());
    assertTrue(s.body().contains("正文指令"));
    assertTrue(Files.isRegularFile(oryxosRoot.resolve("skills/brainstorming/SKILL.md")));
    assertTrue(Files.isRegularFile(oryxosRoot.resolve("skills/brainstorming/scripts/run.py")));
    assertTrue(Files.isRegularFile(oryxosRoot.resolve("skills/brainstorming/REFERENCE.md")));
    assertTrue(registry.get("brainstorming").isPresent());
  }

  @Test
  @DisplayName("importFiles：nameOverride 优先于 frontmatter 的 name")
  void importFiles_nameOverrideTakesPriority() throws Exception {
    Map<String, String> files =
        Map.of("SKILL.md", "---\n\"name\": ignored\ndescription: 覆盖名\n---\n正文");

    Skill s = service.importFiles("myname", files, "fb");

    assertEquals("myname", s.name());
    assertTrue(Files.isRegularFile(oryxosRoot.resolve("skills/myname/SKILL.md")));
    assertTrue(
        Files.readString(oryxosRoot.resolve("skills/myname/SKILL.md")).contains("name: myname"));
  }

  @Test
  @DisplayName("importFiles：落盘后校验失败会删除完整残留目录")
  void importFiles_validationFailureRollsBackDirectory() {
    SkillLoader failingLoader =
        new SkillLoader(oryxosRoot.resolve("skills")) {
          @Override
          public Skill deriveSkill(Path skillDir) {
            throw new IllegalArgumentException("forced validation failure");
          }
        };
    SkillService failingService = new SkillService(store, new SkillRegistry(), failingLoader);
    Map<String, String> files =
        Map.of(
            "SKILL.md", "---\nname: rollback\ndescription: 回滚\n---\n正文",
            "scripts/run.sh", "echo bad");

    assertThrows(
        IllegalArgumentException.class, () -> failingService.importFiles(null, files, "fallback"));

    assertFalse(Files.exists(oryxosRoot.resolve("skills/rollback")));
  }

  @Test
  @DisplayName("importFiles：检测无 SKILL.md 的同名残留且不删除已有文件")
  void importFiles_rejectsResidueWithoutDeletingIt() throws Exception {
    Path residue = Files.createDirectories(oryxosRoot.resolve("skills/residue/scripts"));
    Path existing = Files.writeString(residue.resolve("keep.sh"), "keep");
    Map<String, String> files = Map.of("SKILL.md", "---\nname: residue\ndescription: 新导入\n---\n正文");

    assertThrows(
        IllegalArgumentException.class, () -> service.importFiles(null, files, "fallback"));

    assertEquals("keep", Files.readString(existing));
    assertFalse(Files.exists(oryxosRoot.resolve("skills/residue/SKILL.md")));
  }

  @Test
  @DisplayName("importFiles：注册后的失败同时回滚磁盘与内存索引")
  void importFiles_postRegistrationFailureRollsBackRegistry() {
    AgentSkillBindingService bindings = mock(AgentSkillBindingService.class);
    doThrow(new IllegalStateException("forced reconcile failure"))
        .when(bindings)
        .logCurrentIssues();
    SkillLoader loader = new SkillLoader(oryxosRoot.resolve("skills"));
    SkillService failingService = new SkillService(store, registry, loader, bindings);
    Map<String, String> files =
        Map.of("SKILL.md", "---\nname: rollback-registry\ndescription: 回滚\n---\n正文");

    assertThrows(
        IllegalStateException.class, () -> failingService.importFiles(null, files, "fallback"));

    assertFalse(registry.exists("rollback-registry"));
    assertFalse(Files.exists(oryxosRoot.resolve("skills/rollback-registry")));
  }

  @Test
  @DisplayName("importFiles：缺 SKILL.md 拒绝")
  void importFiles_missingSkillMd_rejected() {
    Map<String, String> files = Map.of("README.md", "没有 SKILL.md");

    assertThrows(IllegalArgumentException.class, () -> service.importFiles(null, files, "fb"));
  }

  @Test
  @DisplayName("importFiles：同名已存在拒绝")
  void importFiles_duplicateName_rejected() {
    service.create("dup", "d", "body");
    Map<String, String> files = Map.of("SKILL.md", "---\nname: dup\n---\n正文");

    assertThrows(IllegalArgumentException.class, () -> service.importFiles(null, files, "fb"));
  }

  @Test
  @DisplayName("importFiles：空 Map 拒绝")
  void importFiles_emptyMap_rejected() {
    assertThrows(IllegalArgumentException.class, () -> service.importFiles(null, Map.of(), "fb"));
  }
}
