package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.OryxTool;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.agent.PromptBuilder;
import io.oryxos.core.agent.ReActLoop;
import io.oryxos.core.agent.ToolExecutor;
import io.oryxos.core.context.ContextLoader;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.provider.ProviderRequest;
import io.oryxos.core.provider.ProviderResponse;
import io.oryxos.core.provider.ProviderService;
import io.oryxos.core.provider.ToolCallRequest;
import io.oryxos.core.provider.Usage;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import io.oryxos.core.skill.AgentSkillCatalog;
import io.oryxos.core.skill.AgentSkillCoordinator;
import io.oryxos.core.skill.AgentSkillLockRegistry;
import io.oryxos.core.skill.SkillContentValidator;
import io.oryxos.core.skill.SkillLimits;
import io.oryxos.core.skill.SkillMetadataReader;
import io.oryxos.storage.JpaToolInvocationAuditor;
import io.oryxos.storage.ToolInvocation;
import io.oryxos.storage.ToolInvocationRepository;
import io.oryxos.tool.ToolRegistry;
import io.oryxos.tool.builtin.FileTools;
import io.oryxos.tool.sandbox.PermissiveSandbox;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** End-to-end proof that Skill discovery is L1-first and L2/L3 stay on the existing Tool path. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SkillProgressiveDisclosureE2ETest.JpaConfig.class)
class SkillProgressiveDisclosureE2ETest {

  private static final String AGENT = "ops-agent";
  private static final String WEATHER_L2 = "WEATHER_L2_UNIQUE_BODY";
  private static final String WEATHER_L3 = "WEATHER_L3_UNIQUE_REFERENCE";
  private static final String FINANCE_L2 = "FINANCE_L2_MUST_NEVER_LOAD";
  private static final String FINANCE_L3 = "FINANCE_L3_MUST_NEVER_LOAD";
  private static final ObjectMapper JSON = new ObjectMapper();

  @TestConfiguration(proxyBeanMethods = false)
  @EnableJpaRepositories(basePackageClasses = ToolInvocationRepository.class)
  @EntityScan(basePackageClasses = ToolInvocation.class)
  static class JpaConfig {}

  @TempDir static Path databaseDir;

  @Autowired private ToolInvocationRepository repository;

  @DynamicPropertySource
  static void sqliteProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url", () -> "jdbc:sqlite:" + databaseDir.resolve("skill-e2e.db"));
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    registry.add(
        "spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
    registry.add("spring.sql.init.mode", () -> "always");
  }

  @BeforeEach
  void clearAudits() {
    repository.deleteAll();
    repository.flush();
  }

  @Test
  void firstPromptHasOnlyL1AndOnlyTheMatchedSkillUsesReadFile(@TempDir Path tempDir)
      throws Exception {
    Path oryxosRoot = Files.createDirectories(tempDir.resolve(".oryxos"));
    Path agentDir = Files.createDirectories(oryxosRoot.resolve("agents").resolve(AGENT));
    Files.writeString(
        agentDir.resolve("AGENT.md"),
        "---\nname: ops-agent\n---\nYou are the operations test Agent.\n");
    Path weatherDir =
        writeSkill(agentDir, "weather", "Weather and travel guidance", WEATHER_L2, WEATHER_L3);
    Path financeDir = writeSkill(agentDir, "finance", "Finance guidance", FINANCE_L2, FINANCE_L3);

    Profile profile = profile();
    ProfileRegistry profiles = new ProfileRegistry(Map.of(AGENT, profile));
    SkillLimits limits = SkillLimits.defaults();
    AgentSkillCatalog catalog =
        new AgentSkillCatalog(
            oryxosRoot.resolve("agents"),
            new SkillMetadataReader(),
            new SkillContentValidator(),
            limits);
    AgentSkillCoordinator coordinator =
        new AgentSkillCoordinator(
            oryxosRoot.resolve("agents"), profiles, catalog, new AgentSkillLockRegistry());

    ToolRegistry registry = new ToolRegistry();
    registry.registerAnnotated(new FileTools(new PermissiveSandbox()));
    Map<String, OryxTool> tools = registry.asMap();
    ToolExecutor executor = new ToolExecutor(tools, new JpaToolInvocationAuditor(repository));
    ScriptedProvider provider =
        new ScriptedProvider(
            weatherDir.resolve("SKILL.md"), weatherDir.resolve("references/details.md"));
    PromptBuilder prompts =
        new PromptBuilder(
            new ContextLoader(oryxosRoot),
            tools,
            Clock.fixed(Instant.parse("2026-07-22T10:00:00Z"), ZoneOffset.UTC));
    ReActLoop loop = new ReActLoop(prompts, provider, executor);
    SessionManager sessions = mock(SessionManager.class);
    AgentService service = new AgentService(profiles, loop, sessions, coordinator);
    Session session = new Session("skill-e2e", AGENT);

    assertEquals("done", service.process(session, "What should I wear outside?"));

    assertEquals(3, provider.requests.size(), "two file reads require three ReAct rounds");
    String firstPrompt = requestText(provider.requests.get(0));
    assertTrue(firstPrompt.contains("- name: finance"));
    assertTrue(firstPrompt.contains("- name: weather"));
    assertTrue(firstPrompt.contains(financeDir.resolve("SKILL.md").toString()));
    assertTrue(firstPrompt.contains(weatherDir.resolve("SKILL.md").toString()));
    assertFalse(firstPrompt.contains(WEATHER_L2));
    assertFalse(firstPrompt.contains(WEATHER_L3));
    assertFalse(firstPrompt.contains(FINANCE_L2));
    assertFalse(firstPrompt.contains(FINANCE_L3));

    assertTrue(requestText(provider.requests.get(1)).contains(WEATHER_L2));
    assertTrue(requestText(provider.requests.get(2)).contains(WEATHER_L3));
    for (ProviderRequest request : provider.requests) {
      assertFalse(requestText(request).contains(FINANCE_L2));
      assertFalse(requestText(request).contains(FINANCE_L3));
      assertEquals(
          List.of("read_file"), request.availableTools().stream().map(OryxTool::getName).toList());
      assertFalse(
          request.availableTools().stream().map(OryxTool::getName).anyMatch("use_skill"::equals));
    }

    repository.flush();
    List<ToolInvocation> invocations = repository.findBySessionId("skill-e2e");
    assertEquals(2, invocations.size(), "both read_file calls must be persisted to SQLite");
    assertTrue(invocations.stream().allMatch(item -> "read_file".equals(item.getToolName())));
    assertTrue(invocations.stream().allMatch(ToolInvocation::isSuccess));
    assertTrue(invocations.stream().allMatch(item -> item.getId() != null));
    assertTrue(invocations.stream().allMatch(item -> item.getCreatedAt() != null));
    assertTrue(
        invocations.stream()
            .map(ToolInvocation::getInputJson)
            .noneMatch(input -> input.contains(financeDir.toString())));
    verify(sessions).save(session);
  }

  private static Path writeSkill(
      Path agentDir, String name, String description, String bodyMarker, String resourceMarker)
      throws Exception {
    Path skillDir = Files.createDirectories(agentDir.resolve("skills").resolve(name));
    Files.writeString(
        skillDir.resolve("SKILL.md"),
        "---\nname: "
            + name
            + "\ndescription: "
            + description
            + "\n---\n"
            + bodyMarker
            + "\nRead references/details.md when needed.\n");
    Path references = Files.createDirectory(skillDir.resolve("references"));
    Files.writeString(references.resolve("details.md"), resourceMarker);
    return skillDir;
  }

  private static Profile profile() {
    return new Profile(
        AGENT,
        "Skill E2E",
        new Profile.Identity("ops", "Use the matching Skill when relevant."),
        new Profile.ProviderRef("scripted", "scripted", null),
        List.of("read_file"),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Profile.Settings.defaults());
  }

  private static String readFileArguments(Path path) {
    return JSON.createObjectNode().put("path", path.toString()).toString();
  }

  private static String requestText(ProviderRequest request) {
    StringBuilder text = new StringBuilder(request.systemPrompt());
    request.messages().stream()
        .map(message -> message.content())
        .filter(content -> content != null)
        .forEach(text::append);
    return text.toString();
  }

  private static final class ScriptedProvider implements ProviderService {

    private final Path skillEntry;
    private final Path reference;
    private final List<ProviderRequest> requests = new ArrayList<>();

    private ScriptedProvider(Path skillEntry, Path reference) {
      this.skillEntry = skillEntry;
      this.reference = reference;
    }

    @Override
    public ProviderResponse chat(String sessionId, Profile profile, ProviderRequest request) {
      requests.add(request);
      return switch (requests.size()) {
        case 1 ->
            new ProviderResponse(
                "",
                List.of(new ToolCallRequest("read_file", readFileArguments(skillEntry))),
                new Usage(1, 1, 2));
        case 2 ->
            new ProviderResponse(
                "",
                List.of(new ToolCallRequest("read_file", readFileArguments(reference))),
                new Usage(1, 1, 2));
        default -> new ProviderResponse("done", List.of(), new Usage(1, 1, 2));
      };
    }
  }
}
