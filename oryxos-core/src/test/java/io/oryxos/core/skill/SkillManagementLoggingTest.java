package io.oryxos.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

/** Cross-action audit contract: one safe management event per service mutation invocation. */
class SkillManagementLoggingTest {

  private static final String AGENT = "ops-agent";
  private static final String SECRET_BODY = "SKILL_BODY_SECRET_MUST_NEVER_REACH_LOGS";

  @TempDir Path tempDir;

  private SkillManagementService service;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void setUp() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve(".oryxos"));
    Path agents = Files.createDirectory(root.resolve("agents"));
    Path agent = Files.createDirectory(agents.resolve(AGENT));
    Files.createDirectory(agent.resolve("skills"));
    ProfileRegistry profiles = new ProfileRegistry(Map.of(AGENT, profile()));
    SkillLimits limits = SkillLimits.defaults();
    AgentSkillCatalog catalog =
        new AgentSkillCatalog(
            agents, new SkillMetadataReader(), new SkillContentValidator(), limits);
    service =
        new SkillManagementService(
            agents,
            profiles,
            catalog,
            new SkillPackageImporter(root, limits),
            new AgentSkillLockRegistry());

    appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(SkillManagementService.class)).addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(SkillManagementService.class)).detachAppender(appender);
    appender.stop();
  }

  @Test
  void importDisableEnableAndDeleteEachEmitOneSanitizedManagementEvent() throws Exception {
    mutate(
        "import",
        () ->
            service.importSkill(
                AGENT,
                new ByteArrayInputStream(validArchive()),
                "/Users/operator/private/sk-test-secret-value.zip"));
    mutate("disable", () -> service.setEnabled(AGENT, "weather", false));
    mutate("enable", () -> service.setEnabled(AGENT, "weather", true));
    mutate(
        "delete",
        () -> {
          service.delete(AGENT, "weather");
          return null;
        });

    String rendered =
        managementEvents().stream()
            .map(event -> event.getFormattedMessage() + String.valueOf(event.getKeyValuePairs()))
            .reduce("", String::concat);
    assertFalse(rendered.contains(SECRET_BODY));
    assertFalse(rendered.contains(tempDir.toString()));
    assertFalse(rendered.contains("/Users/operator"));
    assertFalse(rendered.contains("sk-test-secret-value"));
  }

  private void mutate(String expectedAction, ThrowingSupplier<?> mutation) throws Exception {
    int before = managementEvents().size();
    mutation.get();
    List<ILoggingEvent> events = managementEvents();
    assertEquals(before + 1, events.size());
    ILoggingEvent event = events.get(events.size() - 1);
    assertEquals("skill.management", keyValue(event, "event"));
    assertEquals(expectedAction, keyValue(event, "action"));
    assertEquals("success", keyValue(event, "result"));
  }

  private List<ILoggingEvent> managementEvents() {
    return appender.list.stream()
        .filter(event -> "skill.management".equals(keyValue(event, "event")))
        .toList();
  }

  private static String keyValue(ILoggingEvent event, String key) {
    List<KeyValuePair> values = event.getKeyValuePairs();
    if (values == null) {
      return null;
    }
    return values.stream()
        .filter(pair -> pair.key.equals(key))
        .map(pair -> String.valueOf(pair.value))
        .findFirst()
        .orElse(null);
  }

  private static byte[] validArchive() throws Exception {
    String markdown =
        "---\n"
            + "name: weather\n"
            + "description: Weather guidance\n"
            + "---\n"
            + SECRET_BODY
            + "\n";
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
      zip.putNextEntry(new ZipEntry("weather/SKILL.md"));
      zip.write(markdown.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return bytes.toByteArray();
  }

  private static Profile profile() {
    return new Profile(
        AGENT,
        "Skill management logging test",
        new Profile.Identity("ops", "Use matching Skills."),
        new Profile.ProviderRef("mock", "mock", null),
        List.of("read_file"),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Profile.Settings.defaults());
  }

  @FunctionalInterface
  private interface ThrowingSupplier<T> {
    T get() throws Exception;
  }
}
