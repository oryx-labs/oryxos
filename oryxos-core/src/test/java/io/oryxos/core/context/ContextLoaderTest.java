package io.oryxos.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

/** 课件《第17节》验收 harness：ContextLoaderTest（第29节改造：注入 AGENT.md 正文、去 skills 全文注入）。 */
class ContextLoaderTest {

  @TempDir Path oryxosRoot;

  private ContextLoader loader;
  private Path agentDir;
  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void setUp() throws IOException {
    agentDir = oryxosRoot.resolve("agents").resolve("ops-agent");
    Files.createDirectories(agentDir);
    loader = new ContextLoader(oryxosRoot);
    logAppender = new ListAppender<>();
    logAppender.start();
    ((Logger) LoggerFactory.getLogger(ContextLoader.class)).addAppender(logAppender);
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(ContextLoader.class)).detachAppender(logAppender);
  }

  private Profile profileWith(List<String> bootstrap) {
    return new Profile(
        "ops-agent",
        null,
        new Profile.Identity("运维小欧", "你是一个专业的运维助手"),
        new Profile.ProviderRef("deepseek", "deepseek-chat", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        bootstrap,
        Profile.Settings.defaults());
  }

  private void writeAgentBody(String body) throws IOException {
    Files.writeString(agentDir.resolve("AGENT.md"), "---\nname: ops-agent\n---\n" + body);
  }

  @Test
  @DisplayName("identity+AGENT.md 正文+Bootstrap 按序拼接")
  void loadConcatenatesIdentityBodyAndBootstrapInOrder() throws IOException {
    writeAgentBody("agent-body-content");
    Files.writeString(oryxosRoot.resolve("AGENTS.md"), "agents-content");
    Files.writeString(oryxosRoot.resolve("SOUL.md"), "soul-content");

    String context = loader.load(profileWith(List.of("AGENTS.md", "SOUL.md")));

    int identity = context.indexOf("你是一个专业的运维助手");
    int body = context.indexOf("agent-body-content");
    int agents = context.indexOf("agents-content");
    int soul = context.indexOf("soul-content");
    assertTrue(identity >= 0 && body > identity, "identity 在最前，其后是 AGENT.md 正文");
    assertTrue(agents > body, "AGENT.md 正文在 Bootstrap 之前");
    assertTrue(soul > agents, "Bootstrap 按 Profile 声明顺序");
  }

  @Test
  @DisplayName("改 AGENT.md 正文后下一次 load 立即读到新正文（无缓存回归）")
  void modifiedAgentBodyIsReadOnNextLoadWithoutCache() throws IOException {
    writeAgentBody("body-v1");
    Profile profile = profileWith(List.of());
    assertTrue(loader.load(profile).contains("body-v1"));

    writeAgentBody("body-v2");

    String reloaded = loader.load(profile);
    assertTrue(reloaded.contains("body-v2"), "用户改完正文，下一次组装立即生效");
    assertEquals(-1, reloaded.indexOf("body-v1"));
  }

  @Test
  @DisplayName("改 Bootstrap 文件后下一次 build 立即读到新内容（无缓存回归）")
  void modifiedBootstrapFileIsReadOnNextLoadWithoutCache() throws IOException {
    Files.writeString(oryxosRoot.resolve("AGENTS.md"), "v1");
    Profile profile = profileWith(List.of("AGENTS.md"));
    assertTrue(loader.load(profile).contains("v1"));

    Files.writeString(oryxosRoot.resolve("AGENTS.md"), "v2");

    String reloaded = loader.load(profile);
    assertTrue(reloaded.contains("v2"), "用户改完文件，下一次组装立即生效");
    assertEquals(-1, reloaded.indexOf("v1"));
  }

  @Test
  @DisplayName("Bootstrap 缺失 WARN（不静默跳过、不阻断）")
  void missingBootstrapFileLogsWarnAndContinues() {
    String context = loader.load(profileWith(List.of("USER.md")));

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
