package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.profile.ProfileValidationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** 课件《第29节》验收 harness：AgentLoaderTest——目录拆解、点名报错、坏 Agent 不阻断。 */
class AgentLoaderTest {

  @TempDir Path agentsDir;

  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void setUp() {
    logAppender = new ListAppender<>();
    logAppender.start();
    ((Logger) LoggerFactory.getLogger(AgentLoader.class)).addAppender(logAppender);
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(AgentLoader.class)).detachAppender(logAppender);
  }

  private Path writeAgent(String name, String frontmatter, String body) throws IOException {
    Path dir = Files.createDirectories(agentsDir.resolve(name));
    Files.writeString(dir.resolve("AGENT.md"), "---\n" + frontmatter + "\n---\n" + body);
    return dir;
  }

  private static AgentLoader loader(Path agentsDir) {
    return new AgentLoader(agentsDir, Set.of("deepseek"));
  }

  @Test
  @DisplayName("拆出 frontmatter/正文、认 scripts/skills/REFERENCE.md 资源")
  void deriveProfile_parsesFrontmatterAndToleratesResources() throws IOException {
    Path dir =
        writeAgent(
            "ops", "name: ops\nprovider:\n  name: deepseek\n  model: deepseek-chat", "你是运维助手。");
    Files.createDirectories(dir.resolve("scripts"));
    Files.createDirectories(dir.resolve("skills"));
    Files.writeString(dir.resolve("REFERENCE.md"), "参考");

    ProfileRegistry reg = loader(agentsDir).loadAll();

    assertTrue(reg.get("ops").isPresent(), "带 scripts/skills/REFERENCE.md 的目录正常派生");
    assertEquals("deepseek", reg.get("ops").orElseThrow().provider().name());
  }

  @Test
  @DisplayName("缺 name → 报错点名")
  void deriveProfile_missingName_throwsNamedError() throws IOException {
    writeAgent("bad", "provider:\n  name: deepseek\n  model: m", "x");

    ProfileValidationException ex =
        assertThrows(
            ProfileValidationException.class,
            () -> loader(agentsDir).deriveProfile(agentsDir.resolve("bad")));

    assertTrue(ex.getMessage().contains("name"), "报错含缺失字段名");
    assertTrue(ex.getMessage().contains("bad"), "点名是哪个 Agent 目录");
  }

  @Test
  @DisplayName("缺 provider → 报错点名")
  void deriveProfile_missingProvider_throwsNamedError() throws IOException {
    writeAgent("noprov", "name: noprov", "x");

    ProfileValidationException ex =
        assertThrows(
            ProfileValidationException.class,
            () -> loader(agentsDir).deriveProfile(agentsDir.resolve("noprov")));

    assertTrue(ex.getMessage().contains("provider"), "报错含 provider");
  }

  @Test
  @DisplayName("一坏一好目录 → 好的仍登记、坏的记错误不阻断")
  void loadAll_badAgentDoesNotBlockGoodOnes() throws IOException {
    writeAgent("good", "name: good\nprovider:\n  name: deepseek\n  model: deepseek-chat", "ok");
    writeAgent("broken", "provider:\n  name: deepseek\n  model: m", "no name"); // 缺 name

    ProfileRegistry reg = loader(agentsDir).loadAll();

    assertTrue(reg.exists("good"), "好的仍登记");
    assertFalse(reg.exists("broken"), "坏的被跳过");
    assertEquals(1, reg.all().size(), "扫描不产生别的东西");
  }

  @Test
  @DisplayName("tools 引用未注册能力 → 告警但仍登记")
  void deriveProfile_unknownTool_warnsButRegisters() throws IOException {
    writeAgent(
        "toolagent",
        "name: toolagent\nprovider:\n  name: deepseek\n  model: deepseek-chat\ntools:\n  - ghost_tool",
        "x");

    ProfileRegistry reg =
        new AgentLoader(agentsDir, Set.of("deepseek"), Set.of("read_file")).loadAll();

    assertTrue(reg.exists("toolagent"), "引用未注册能力不阻断登记");
    boolean warned =
        logAppender.list.stream()
            .anyMatch(
                e ->
                    "WARN".equals(e.getLevel().toString())
                        && e.getFormattedMessage().contains("ghost_tool"));
    assertTrue(warned, "引用未注册能力至少 WARN");
  }
}
