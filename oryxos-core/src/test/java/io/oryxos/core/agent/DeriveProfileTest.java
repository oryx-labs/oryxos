package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.oryxos.core.profile.Profile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 课件《第29节》验收 harness：DeriveProfileTest——frontmatter 各字段正确映射、schedules 带进 Profile。 */
class DeriveProfileTest {

  @TempDir Path agentsDir;

  private Path writeAgent(String name, String frontmatter) throws IOException {
    Path dir = Files.createDirectories(agentsDir.resolve(name));
    Files.writeString(dir.resolve("AGENT.md"), "---\n" + frontmatter + "\n---\n正文指令");
    return dir;
  }

  @Test
  @DisplayName("frontmatter 各字段正确映射到 Profile")
  void deriveProfile_mapsFrontmatterFields() throws IOException {
    Path dir =
        writeAgent(
            "ops",
            "name: ops\n"
                + "description: 运维助手\n"
                + "identity:\n  agent_name: 小欧\n  prompt: 你是运维助手\n"
                + "provider:\n  name: deepseek\n  model: deepseek-chat\n  temperature: 0.2\n"
                + "tools:\n  - shell\n  - read_file\n"
                + "notify_channels:\n  - {type: webhook, url: http://hook.local}\n"
                + "settings:\n  max_iterations: 7\n  max_history_turns: 15");

    Profile p = new AgentLoader(agentsDir, Set.of("deepseek")).deriveProfile(dir);

    assertEquals("ops", p.name());
    assertEquals("运维助手", p.description());
    assertEquals("小欧", p.identity().agentName());
    assertEquals("你是运维助手", p.identity().prompt());
    assertEquals("deepseek", p.provider().name());
    assertEquals("deepseek-chat", p.provider().model());
    assertEquals(0.2, p.provider().temperature());
    assertEquals(java.util.List.of("shell", "read_file"), p.tools());
    assertEquals(1, p.notifyChannels().size());
    assertEquals("webhook", p.notifyChannels().get(0).type());
    assertEquals("http://hook.local", p.notifyChannels().get(0).config().get("url"));
    assertEquals(7, p.settings().maxIterations());
    assertEquals(15, p.settings().maxHistoryTurns());
  }

  @Test
  @DisplayName("schedules 原样带进派生的 Profile（定时来自 Agent）")
  void deriveProfile_carriesSchedulesFromFrontmatter() throws IOException {
    Path dir =
        writeAgent(
            "cron-agent",
            "name: cron-agent\n"
                + "provider:\n  name: deepseek\n  model: deepseek-chat\n"
                + "schedules:\n"
                + "  - {id: morning, cron: \"0 0 9 * * *\", zone: Asia/Shanghai, message: 到点了}");

    Profile p = new AgentLoader(agentsDir, Set.of("deepseek")).deriveProfile(dir);

    assertEquals(1, p.schedules().size());
    Profile.ScheduleConfig sc = p.schedules().get(0);
    assertEquals("morning", sc.id());
    assertEquals("0 0 9 * * *", sc.cron());
    assertEquals("Asia/Shanghai", sc.zone());
    assertEquals("到点了", sc.message());
  }
}
