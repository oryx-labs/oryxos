package io.oryxos.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.oryxos.core.profile.Profile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** 课件《第17节》验收 harness：ContextLoaderTest。 */
class ContextLoaderTest {

  @TempDir Path oryxosRoot;

  private ContextLoader loader;
  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void setUp() throws IOException {
    Files.createDirectories(oryxosRoot.resolve("skills"));
    loader = new ContextLoader(oryxosRoot);
    logAppender = new ListAppender<>();
    logAppender.start();
    ((Logger) LoggerFactory.getLogger(ContextLoader.class)).addAppender(logAppender);
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(ContextLoader.class)).detachAppender(logAppender);
  }

  private Profile profileWith(List<String> bootstrap, List<String> skills) {
    return new Profile(
        "ops-agent",
        null,
        new Profile.Identity("运维小欧", "你是一个专业的运维助手"),
        new Profile.ProviderRef("deepseek", "deepseek-chat", null),
        List.of(),
        skills,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        bootstrap,
        Profile.Settings.defaults());
  }

  @Test
  @DisplayName("identity+Bootstrap+Skill 按序拼接")
  void loadConcatenatesIdentityBootstrapAndSkillsInOrder() throws IOException {
    Files.writeString(oryxosRoot.resolve("AGENTS.md"), "agents-content");
    Files.writeString(oryxosRoot.resolve("SOUL.md"), "soul-content");
    Files.writeString(oryxosRoot.resolve("skills").resolve("daily-pr-digest.md"), "skill-content");

    String context =
        loader.load(profileWith(List.of("AGENTS.md", "SOUL.md"), List.of("daily-pr-digest")));

    int identity = context.indexOf("你是一个专业的运维助手");
    int agents = context.indexOf("agents-content");
    int soul = context.indexOf("soul-content");
    int skill = context.indexOf("skill-content");
    assertTrue(identity >= 0 && agents > identity, "identity 在最前，其后是 Bootstrap");
    assertTrue(soul > agents, "Bootstrap 按 Profile 声明顺序");
    assertTrue(skill > soul, "Skill 在 Bootstrap 之后");
  }

  @Test
  @DisplayName("改文件后下一次 build 立即读到新内容（无缓存回归）")
  void modifiedFileIsReadOnNextLoadWithoutCache() throws IOException {
    Files.writeString(oryxosRoot.resolve("AGENTS.md"), "v1");
    Profile profile = profileWith(List.of("AGENTS.md"), List.of());
    assertTrue(loader.load(profile).contains("v1"));

    Files.writeString(oryxosRoot.resolve("AGENTS.md"), "v2");

    String reloaded = loader.load(profile);
    assertTrue(reloaded.contains("v2"), "用户改完文件，下一次组装立即生效");
    assertEquals(-1, reloaded.indexOf("v1"));
  }

  @Test
  @DisplayName("Skill 引用缺失报错（点名文件）")
  void missingSkillFileThrowsWithFileName() {
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> loader.load(profileWith(List.of(), List.of("nonexistent-skill"))));

    assertTrue(ex.getMessage().contains("nonexistent-skill"), "报错必须点名缺失的 Skill");
  }

  @Test
  @DisplayName("Bootstrap 缺失 WARN（不静默跳过、不阻断）")
  void missingBootstrapFileLogsWarnAndContinues() {
    String context = loader.load(profileWith(List.of("USER.md"), List.of()));

    assertTrue(context.contains("你是一个专业的运维助手"), "identity 部分照常返回");
    boolean warned =
        logAppender.list.stream()
            .anyMatch(
                e ->
                    "WARN".equals(e.getLevel().toString())
                        && e.getFormattedMessage().contains("USER.md"));
    assertTrue(warned, "Bootstrap 缺失至少 WARN——静默跳过会造成人格悄悄丢失");
  }
}
