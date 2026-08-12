package io.oryxos.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.AgentLoader;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.skill.AgentSkillBindingService;
import io.oryxos.core.skill.AgentSkillStartupReport;
import io.oryxos.core.skill.SkillLoader;
import io.oryxos.core.skill.SkillRegistry;
import io.oryxos.core.skill.SkillService;
import io.oryxos.core.skill.SkillStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class AgentSkillStartupOrderTest {

  @TempDir Path root;

  @Test
  void builtinsSeedBeforeMigrationAndMigrationFinishesBeforeProfiles() throws Exception {
    legacyAgent("good", "report-format");
    legacyAgent("bad", "missing");
    OryxOsRuntime runtime = new OryxOsRuntime();
    ReflectionTestUtils.setField(runtime, "oryxosRootProp", root.toString());

    SkillStore store = runtime.skillStore();
    SkillLoader skillLoader = runtime.skillLoader();
    SkillRegistry skillRegistry = runtime.skillRegistry(skillLoader);
    AgentSkillBindingService bindings = runtime.agentSkillBindingService(skillLoader);
    SkillService seeded = runtime.skillService(store, skillRegistry, skillLoader, bindings);
    AgentSkillStartupReport report = runtime.agentSkillStartupReport(seeded, bindings);
    AgentLoader agents = new AgentLoader(root.resolve("agents"), Set.of("mock"));
    ProfileRegistry profiles = runtime.profileRegistry(agents, report);

    assertTrue(Files.isRegularFile(root.resolve("skills/report-format/SKILL.md")));
    assertTrue(Files.isSymbolicLink(root.resolve("agents/good/skills/report-format")));
    assertFalse(Files.readString(root.resolve("agents/good/AGENT.md")).contains("skills:"));
    assertTrue(Files.readString(root.resolve("agents/bad/AGENT.md")).contains("skills:"));
    assertTrue(profiles.exists("good"));
    assertTrue(profiles.exists("bad"), "单 Agent 迁移失败不阻断其它 Profile 启动");
  }

  private void legacyAgent(String name, String skill) throws Exception {
    Path directory = Files.createDirectories(root.resolve("agents").resolve(name));
    Files.writeString(
        directory.resolve("AGENT.md"),
        "---\nname: "
            + name
            + "\nprovider:\n  name: mock\n  model: mock\nskills:\n  - "
            + skill
            + "\n---\nbody");
  }
}
