package io.oryxos.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
  void importFiles_nameOverrideTakesPriority() {
    Map<String, String> files = Map.of("SKILL.md", "---\nname: ignored\n---\n正文");

    Skill s = service.importFiles("myname", files, "fb");

    assertEquals("myname", s.name());
    assertTrue(Files.isRegularFile(oryxosRoot.resolve("skills/myname/SKILL.md")));
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
