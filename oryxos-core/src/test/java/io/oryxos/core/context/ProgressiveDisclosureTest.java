package io.oryxos.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.profile.Profile;
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
    loader = new ContextLoader(oryxosRoot);
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
}
