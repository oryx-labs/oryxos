package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 课件《第29节》验收 harness：AgentScanRegisterTest——扫 N 个目录得 N 个 Agent、不产生别的东西。 */
class AgentScanRegisterTest {

  @TempDir Path agentsDir;

  private void writeAgent(String name, boolean withSchedule) throws IOException {
    Path dir = Files.createDirectories(agentsDir.resolve(name));
    StringBuilder fm = new StringBuilder();
    fm.append("name: ").append(name).append('\n');
    fm.append("provider:\n  name: deepseek\n  model: deepseek-chat\n");
    if (withSchedule) {
      fm.append("schedules:\n  - {cron: \"0 0 9 * * *\", zone: Asia/Shanghai, message: 到点}\n");
    }
    Files.writeString(dir.resolve("AGENT.md"), "---\n" + fm + "---\n正文");
  }

  @Test
  @DisplayName("扫 N 个 Agent 目录 → 注册表出现 N 个、不产生别的东西")
  void scanNDirs_yieldsExactlyNAgents() throws IOException {
    writeAgent("alpha", false);
    writeAgent("beta", true);
    writeAgent("gamma", false);

    ProfileRegistry reg = new AgentLoader(agentsDir, Set.of("deepseek")).loadAll();

    assertEquals(3, reg.all().size(), "扫 3 个目录得 3 个 Agent");
    Set<String> names = reg.all().stream().map(Profile::name).collect(Collectors.toSet());
    assertEquals(Set.of("alpha", "beta", "gamma"), names, "名字一一对应、不多不少");
  }

  @Test
  @DisplayName("带 schedules 的 Agent 其 Profile 携带定时（供 AgentScheduler 注册）")
  void scan_scheduledAgentCarriesSchedule() throws IOException {
    writeAgent("scheduled", true);
    writeAgent("plain", false);

    ProfileRegistry reg = new AgentLoader(agentsDir, Set.of("deepseek")).loadAll();

    assertTrue(reg.get("scheduled").orElseThrow().schedules().size() == 1, "带定时的携带 schedules");
    assertTrue(reg.get("plain").orElseThrow().schedules().isEmpty(), "不带定时的为空");
  }
}
