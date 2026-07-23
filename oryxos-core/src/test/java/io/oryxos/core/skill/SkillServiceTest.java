package io.oryxos.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 第 32 节验收 harness：全局 Skill 库的 CRUD + 内置播种 + 落盘/索引同步。 */
class SkillServiceTest {

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
  @DisplayName("create：落盘 SKILL.md + 登记内存索引 + 解析出正文")
  void create_writesAndRegisters() {
    Skill s = service.create("report-fmt", "研报格式", "# 规范\n带出处");
    assertEquals("report-fmt", s.name());
    assertEquals("研报格式", s.description());
    assertTrue(s.body().contains("带出处"));
    assertTrue(Files.isRegularFile(oryxosRoot.resolve("skills/report-fmt/SKILL.md")));
    assertTrue(registry.get("report-fmt").isPresent());
  }

  @Test
  @DisplayName("create 同名 → 400（IllegalArgumentException）")
  void create_duplicate_rejected() {
    service.create("dup", "d", "b");
    assertThrows(IllegalArgumentException.class, () -> service.create("dup", "d2", "b2"));
  }

  @Test
  @DisplayName("create 非法名（路径穿越）→ 拒绝")
  void create_illegalName_rejected() {
    assertThrows(IllegalArgumentException.class, () -> service.create("../evil", "d", "b"));
  }

  @Test
  @DisplayName("update：覆写描述与正文并即时生效")
  void update_overwrites() {
    service.create("s1", "old", "old body");
    Skill updated = service.update("s1", "new", "new body");
    assertEquals("new", updated.description());
    assertTrue(updated.body().contains("new body"));
    assertTrue(registry.get("s1").orElseThrow().body().contains("new body"));
  }

  @Test
  @DisplayName("delete：物理删目录 + 移出索引")
  void delete_removesDirAndIndex() {
    service.create("s2", "d", "b");
    service.delete("s2");
    assertFalse(Files.exists(oryxosRoot.resolve("skills/s2")));
    assertTrue(registry.get("s2").isEmpty());
  }

  @Test
  @DisplayName("seedBuiltins：播种内置 report-format；幂等——用户改过不覆盖")
  void seedBuiltins_idempotentAndRespectsEdits() {
    service.seedBuiltins();
    assertTrue(registry.get(SkillService.BUILTIN_REPORT_FORMAT).isPresent());
    // 用户改正文后再 seed，不应覆盖
    service.update(SkillService.BUILTIN_REPORT_FORMAT, "我的描述", "我的正文");
    service.seedBuiltins();
    assertEquals(
        "我的正文", registry.get(SkillService.BUILTIN_REPORT_FORMAT).orElseThrow().body().strip());
  }

  @Test
  @DisplayName("seedBuiltins：播种一批内置 Skill（含 report-format），数量 ≥ 5")
  void seedBuiltins_seedsBatch() {
    service.seedBuiltins();
    assertTrue(registry.get(SkillService.BUILTIN_REPORT_FORMAT).isPresent());
    assertTrue(service.list().size() >= 5, "应播种一批内置 Skill，而不止一个");
  }

  @Test
  @DisplayName("importMarkdown：解析 frontmatter 建库；name 缺省用 fallback；nameOverride 优先；同名 400")
  void importMarkdown_parsesAndCreates() {
    Skill s =
        service.importMarkdown(null, "---\nname: imp\ndescription: 导入的\n---\n\n正文X", "fallback");
    assertEquals("imp", s.name());
    assertEquals("导入的", s.description());
    assertTrue(s.body().contains("正文X"));

    Skill s2 = service.importMarkdown(null, "没有 frontmatter 的正文", "url-derived");
    assertEquals("url-derived", s2.name());

    Skill s3 = service.importMarkdown("myname", "---\nname: ignored\n---\nz", "fb");
    assertEquals("myname", s3.name());

    assertThrows(
        IllegalArgumentException.class,
        () -> service.importMarkdown(null, "---\nname: imp\n---\nx", "fb"));
  }

  @Test
  @DisplayName("loadAll：扫描既有 SKILL.md 目录建索引")
  void loadAll_scansExisting() {
    store.write("pre", "---\nname: pre\ndescription: 预置\n---\n\n正文");
    SkillRegistry reloaded = new SkillLoader(oryxosRoot.resolve("skills")).loadAll();
    assertTrue(reloaded.get("pre").isPresent());
    assertEquals("预置", reloaded.get("pre").orElseThrow().description());
  }
}
