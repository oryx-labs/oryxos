package io.oryxos.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.skill.AgentSkillBindingService;
import io.oryxos.core.skill.SkillLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 课件《第29节》验收 harness：ProgressiveDisclosureTest——一个 Agent 内部的渐进式披露守点： 正文进 system
 * prompt；子指令/参考/脚本不预载（靠底座 read_file/shell 按需取）；改正文即时生效（无缓存）。
 */
class ProgressiveDisclosureTest {

  @TempDir Path oryxosRoot;

  private ContextLoader loader;
  private Path agentDir;

  @BeforeEach
  void setUp() throws IOException {
    agentDir = oryxosRoot.resolve("agents").resolve("reconcile");
    Files.createDirectories(agentDir);
    loader =
        new ContextLoader(
            oryxosRoot,
            new AgentSkillBindingService(
                oryxosRoot, new SkillLoader(oryxosRoot.resolve("skills"))));
  }

  private Profile profile() {
    return new Profile(
        "reconcile",
        null,
        null,
        new Profile.ProviderRef("deepseek", "deepseek-chat", null),
        List.of("shell", "read_file"),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Profile.Settings.defaults());
  }

  private void writeBody(String body) throws IOException {
    Files.writeString(agentDir.resolve("AGENT.md"), "---\nname: reconcile\n---\n" + body);
  }

  @Test
  @DisplayName("正文进 system prompt；子指令/参考/脚本内容不预载")
  void bodyInjected_subResourcesNotPreloaded() throws IOException {
    writeBody("跑 python scripts/reconcile.py，规范见 skills/report-format.md，拿不准读 REFERENCE.md");
    Files.createDirectories(agentDir.resolve("skills"));
    Files.writeString(
        agentDir.resolve("skills").resolve("report-format.md"), "SUBINSTRUCTION_SECRET");
    Files.writeString(agentDir.resolve("REFERENCE.md"), "REFERENCE_SECRET");
    Files.createDirectories(agentDir.resolve("scripts"));
    Files.writeString(agentDir.resolve("scripts").resolve("reconcile.py"), "SCRIPT_CODE_SECRET");

    String context = loader.load(profile());

    assertTrue(context.contains("跑 python scripts/reconcile.py"), "AGENT.md 正文进 system prompt");
    assertFalse(context.contains("SUBINSTRUCTION_SECRET"), "子指令不预载（用到才 read_file）");
    assertFalse(context.contains("REFERENCE_SECRET"), "参考不预载（用到才 read_file）");
    assertFalse(context.contains("SCRIPT_CODE_SECRET"), "脚本代码不进上下文（用到才 shell 跑、只产出进）");
  }

  @Test
  @DisplayName("改盘上正文后下一次 load 反映新正文（无缓存、不重启即时生效）")
  void bodyEditTakesEffectWithoutRestart() throws IOException {
    writeBody("v1-instruction");
    Profile p = profile();
    assertTrue(loader.load(p).contains("v1-instruction"));

    writeBody("v2-instruction");

    String reloaded = loader.load(p);
    assertTrue(reloaded.contains("v2-instruction"), "改正文下一次触发即生效");
    assertEquals(-1, reloaded.indexOf("v1-instruction"));
  }

  @Test
  @DisplayName("绑定 Skill 每轮只注入 name/description/本地路径，不注入正文或未绑定 Skill")
  void boundSkillOnlyDisclosesMetadata() throws IOException {
    writeBody("按需使用 Skill");
    Path report = oryxosRoot.resolve("skills/report");
    Path hidden = oryxosRoot.resolve("skills/hidden");
    Files.createDirectories(report);
    Files.createDirectories(hidden);
    Files.writeString(
        report.resolve("SKILL.md"), "---\nname: report\ndescription: 报告格式\n---\nBOUND_BODY_SECRET");
    Files.writeString(
        hidden.resolve("SKILL.md"),
        "---\nname: hidden\ndescription: 不可见\n---\nUNBOUND_BODY_SECRET");
    Files.createDirectories(agentDir.resolve("skills"));
    Files.createSymbolicLink(agentDir.resolve("skills/report"), Path.of("../../../skills/report"));

    String context = loader.load(profile());

    assertTrue(context.contains("report"));
    assertTrue(context.contains("报告格式"));
    assertTrue(
        context.contains(
            agentDir.resolve("skills/report/SKILL.md").toAbsolutePath().normalize().toString()));
    assertFalse(context.contains("BOUND_BODY_SECRET"));
    assertFalse(context.contains("hidden"));
    assertFalse(context.contains("UNBOUND_BODY_SECRET"));
  }

  @Test
  @DisplayName("零绑定不输出 Skill 标题，多绑定按名称稳定排序并跳过坏链接")
  void zeroAndMultipleBindingsHaveStableMinimalCatalog() throws IOException {
    writeBody("正文");
    assertFalse(loader.load(profile()).contains("你可以按需使用以下 Skill"));

    for (String name : List.of("zeta", "alpha")) {
      Path skill = Files.createDirectories(oryxosRoot.resolve("skills").resolve(name));
      Files.writeString(
          skill.resolve("SKILL.md"),
          "---\nname: " + name + "\ndescription: " + name + "-description\n---\n" + name + "-body");
    }
    Path links = Files.createDirectories(agentDir.resolve("skills"));
    Files.createSymbolicLink(links.resolve("zeta"), Path.of("../../../skills/zeta"));
    Files.createSymbolicLink(links.resolve("alpha"), Path.of("../../../skills/alpha"));
    Files.createSymbolicLink(links.resolve("broken"), Path.of("../../../skills/missing"));

    String context = loader.load(profile());

    assertTrue(context.indexOf("- alpha：") < context.indexOf("- zeta："));
    assertFalse(context.contains("alpha-body"));
    assertFalse(context.contains("zeta-body"));
    assertFalse(context.contains("broken"));
  }
}
