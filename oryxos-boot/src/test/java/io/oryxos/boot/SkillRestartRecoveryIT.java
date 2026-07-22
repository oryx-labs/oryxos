package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.session.Message;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import io.oryxos.core.skill.AgentSkillCoordinator;
import io.oryxos.core.skill.SkillLease;
import io.oryxos.core.skill.SkillManagementService;
import io.oryxos.core.skill.SkillStatus;
import io.oryxos.storage.LlmCall;
import io.oryxos.storage.LlmCallRepository;
import io.oryxos.storage.SessionRepository;
import io.oryxos.storage.ToolInvocation;
import io.oryxos.storage.ToolInvocationRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Two-context integration proof that the disabled marker is durable and history is append-only. */
@Tag("integration")
class SkillRestartRecoveryIT {

  private static final String AGENT = "ops-agent";
  private static final String SKILL = "weather";
  private static final String INVALID_SKILL = "broken";
  private static final String RETIRED_SKILL = "retired";
  private static final String OLD_SESSION = "web:old-user:" + AGENT;

  @Test
  void markerSurvivesRestartAndToggleNeverRewritesSessionOrAuditHistory() throws Exception {
    Path root = seedWorkspace();
    String databaseUrl = "jdbc:sqlite:" + root.resolve("skill-restart.db");
    List<Message> savedHistory;
    SessionRow savedSessionRow;
    List<ToolAuditRow> savedToolAudits;
    List<LlmAuditRow> savedLlmAudits;

    try (ConfigurableApplicationContext context = boot(root, databaseUrl)) {
      SkillManagementService management = context.getBean(SkillManagementService.class);
      assertEquals(SkillStatus.ENABLED, management.get(AGENT, SKILL).status());
      assertEquals(SkillStatus.INVALID, management.get(AGENT, INVALID_SKILL).status());
      assertFalse(management.get(AGENT, INVALID_SKILL).catalogIncluded());
      assertEquals(SkillStatus.ENABLED, management.get(AGENT, RETIRED_SKILL).status());

      SessionManager sessions = context.getBean(SessionManager.class);
      Session oldSession = sessions.getOrCreate("web", "old-user", AGENT);
      oldSession.appendUser("history-before-toggle");
      sessions.save(oldSession);
      seedAudits(context, OLD_SESSION);
      savedHistory = sessions.get(OLD_SESSION).orElseThrow().messages();
      savedSessionRow = sessionRow(context);
      savedToolAudits = toolAuditRows(context);
      savedLlmAudits = llmAuditRows(context);

      management.delete(AGENT, RETIRED_SKILL);
      assertArchivedPackage(root, RETIRED_SKILL);
      assertThrows(NoSuchElementException.class, () -> management.get(AGENT, RETIRED_SKILL));

      assertEquals(SkillStatus.DISABLED, management.setEnabled(AGENT, SKILL, false).status());
      Path marker = root.resolve("agents/ops-agent/skills/weather/.oryxos-disabled");
      assertTrue(Files.isRegularFile(marker));
      assertEquals(0, Files.size(marker));
      assertSnapshotSkillNames(context, List.of());
      assertHistoryAndRowsUnchanged(
          context, savedHistory, savedSessionRow, savedToolAudits, savedLlmAudits);
    }

    try (ConfigurableApplicationContext context = boot(root, databaseUrl)) {
      SkillManagementService management = context.getBean(SkillManagementService.class);
      assertEquals(SkillStatus.DISABLED, management.get(AGENT, SKILL).status());
      assertFalse(management.get(AGENT, SKILL).configuredEnabled());
      assertEquals(SkillStatus.INVALID, management.get(AGENT, INVALID_SKILL).status());
      assertFalse(management.get(AGENT, INVALID_SKILL).catalogIncluded());
      assertThrows(NoSuchElementException.class, () -> management.get(AGENT, RETIRED_SKILL));
      assertArchivedPackage(root, RETIRED_SKILL);

      Session fresh =
          context.getBean(SessionManager.class).getOrCreate("web", "new-user-after-restart", AGENT);
      assertTrue(fresh.messages().isEmpty());
      assertSnapshotSkillNames(context, List.of());
      String disabledReply =
          context
              .getBean(AgentService.class)
              .process(fresh, "new request while the private Skill is disabled");
      assertFalse(disabledReply.isBlank());
      assertHistoryAndRowsUnchanged(
          context, savedHistory, savedSessionRow, savedToolAudits, savedLlmAudits);

      assertEquals(SkillStatus.ENABLED, management.setEnabled(AGENT, SKILL, true).status());
      assertSnapshotSkillNames(context, List.of(SKILL));
      Session enabledSession =
          context.getBean(SessionManager.class).getOrCreate("web", "new-user-after-enable", AGENT);
      String enabledReply =
          context
              .getBean(AgentService.class)
              .process(enabledSession, "new request after the private Skill is enabled");
      assertFalse(enabledReply.isBlank());
      assertHistoryAndRowsUnchanged(
          context, savedHistory, savedSessionRow, savedToolAudits, savedLlmAudits);
    }
  }

  private static void assertSnapshotSkillNames(
      ConfigurableApplicationContext context, List<String> expected) {
    AgentSkillCoordinator coordinator = context.getBean(AgentSkillCoordinator.class);
    try (SkillLease lease = coordinator.openRequest(AGENT)) {
      assertEquals(
          expected, lease.snapshot().skills().stream().map(skill -> skill.name()).toList());
    }
  }

  private static void assertArchivedPackage(Path root, String skillName) throws IOException {
    Path archiveAgent = root.resolve("archive/.skills").resolve(AGENT);
    List<Path> events;
    try (var children = Files.list(archiveAgent)) {
      events = children.toList();
    }
    assertEquals(1, events.size());
    Path event = events.get(0);
    assertTrue(Files.isRegularFile(event.resolve("archive.yml")));
    assertTrue(Files.isRegularFile(event.resolve("package/SKILL.md")));
    assertTrue(Files.readString(event.resolve("archive.yml")).contains("skill: " + skillName));
  }

  private static void assertHistoryAndRowsUnchanged(
      ConfigurableApplicationContext context,
      List<Message> expectedHistory,
      SessionRow expectedSessionRow,
      List<ToolAuditRow> expectedToolAudits,
      List<LlmAuditRow> expectedLlmAudits) {
    assertEquals(
        expectedHistory,
        context.getBean(SessionManager.class).get(OLD_SESSION).orElseThrow().messages());
    assertEquals(expectedSessionRow, sessionRow(context));
    assertEquals(expectedToolAudits, toolAuditRows(context));
    assertEquals(expectedLlmAudits, llmAuditRows(context));
  }

  private static void seedAudits(ConfigurableApplicationContext context, String sessionId) {
    ToolInvocation tool = new ToolInvocation();
    tool.setSessionId(sessionId);
    tool.setToolName("read_file");
    tool.setInputJson("{\"path\":\"skills/weather/SKILL.md\"}");
    tool.setResultJson("{\"success\":true}");
    tool.setSuccess(true);
    tool.setDurationMs(1);
    context.getBean(ToolInvocationRepository.class).save(tool);

    LlmCall llm = new LlmCall();
    llm.setSessionId(sessionId);
    llm.setProvider("mock");
    llm.setModel("mock-model");
    llm.setPromptTokens(1);
    llm.setCompletionTokens(1);
    llm.setTotalTokens(2);
    llm.setSuccess(true);
    llm.setDurationMs(1);
    context.getBean(LlmCallRepository.class).save(llm);

    context.getBean(ToolInvocationRepository.class).flush();
    context.getBean(LlmCallRepository.class).flush();
  }

  private static SessionRow sessionRow(ConfigurableApplicationContext context) {
    io.oryxos.storage.Session row =
        context.getBean(SessionRepository.class).findById(OLD_SESSION).orElseThrow();
    return new SessionRow(
        row.getSessionId(),
        row.getProfileName(),
        row.getChannel(),
        row.getUserId(),
        row.getMessagesJson(),
        row.getStatus(),
        row.getCreatedAt(),
        row.getLastActiveAt(),
        row.getArchivedAt());
  }

  private static List<ToolAuditRow> toolAuditRows(ConfigurableApplicationContext context) {
    return context.getBean(ToolInvocationRepository.class).findBySessionId(OLD_SESSION).stream()
        .sorted(Comparator.comparing(ToolInvocation::getId))
        .map(
            row ->
                new ToolAuditRow(
                    row.getId(),
                    row.getSessionId(),
                    row.getToolName(),
                    row.getInputJson(),
                    row.getResultJson(),
                    row.isSuccess(),
                    row.getErrorMessage(),
                    row.getDurationMs(),
                    row.getCreatedAt()))
        .toList();
  }

  private static List<LlmAuditRow> llmAuditRows(ConfigurableApplicationContext context) {
    return context.getBean(LlmCallRepository.class).findBySessionId(OLD_SESSION).stream()
        .sorted(Comparator.comparing(LlmCall::getId))
        .map(
            row ->
                new LlmAuditRow(
                    row.getId(),
                    row.getSessionId(),
                    row.getProvider(),
                    row.getModel(),
                    row.getPromptTokens(),
                    row.getCompletionTokens(),
                    row.getTotalTokens(),
                    row.isSuccess(),
                    row.getErrorMessage(),
                    row.getDurationMs(),
                    row.getCreatedAt()))
        .toList();
  }

  private static ConfigurableApplicationContext boot(Path root, String databaseUrl) {
    System.setProperty("nacos.logging.default.config.enabled", "false");
    return new SpringApplicationBuilder(OryxOsRuntime.class)
        .run(
            "--oryxos.root=" + root,
            "--oryxos.providers[0].name=mock",
            "--oryxos.author.provider=mock",
            "--oryxos.author.model=mock-model",
            "--spring.datasource.url=" + databaseUrl,
            "--spring.lifecycle.timeout-per-shutdown-phase=1s",
            "--spring.main.web-application-type=none");
  }

  private static Path seedWorkspace() throws IOException {
    Path root = Files.createTempDirectory("oryxos-skill-restart");
    Files.createDirectories(root.resolve("memory"));
    Path agent = Files.createDirectories(root.resolve("agents").resolve(AGENT));
    Files.writeString(
        agent.resolve("AGENT.md"),
        """
        ---
        name: ops-agent
        description: Skill restart integration Agent
        identity:
          agent_name: Ops
          prompt: Use a matching Skill only when relevant.
        provider:
          name: mock
          model: mock-model
        tools:
          - read_file
        settings:
          max_iterations: 10
          max_history_turns: 20
        ---
        Keep existing conversation and audit history unchanged across Skill management operations.
        """);
    Path skill = Files.createDirectories(agent.resolve("skills").resolve(SKILL));
    Files.writeString(
        skill.resolve("SKILL.md"),
        """
        ---
        name: weather
        description: Weather guidance for restart integration tests
        ---
        Read this body only after the L1 metadata matches the request.
        """);
    Path invalid = Files.createDirectories(agent.resolve("skills").resolve(INVALID_SKILL));
    Files.writeString(invalid.resolve("SKILL.md"), "frontmatter is deliberately missing");
    Path retired = Files.createDirectories(agent.resolve("skills").resolve(RETIRED_SKILL));
    Files.writeString(
        retired.resolve("SKILL.md"),
        """
        ---
        name: retired
        description: Skill archived before the restart boundary
        ---
        This package must stay archived and absent after restart.
        """);
    return root;
  }

  private record SessionRow(
      String sessionId,
      String profileName,
      String channel,
      String userId,
      String messagesJson,
      String status,
      Instant createdAt,
      Instant lastActiveAt,
      Instant archivedAt) {}

  private record ToolAuditRow(
      Long id,
      String sessionId,
      String toolName,
      String inputJson,
      String resultJson,
      boolean success,
      String errorMessage,
      long durationMs,
      Instant createdAt) {}

  private record LlmAuditRow(
      Long id,
      String sessionId,
      String provider,
      String model,
      Integer promptTokens,
      Integer completionTokens,
      Integer totalTokens,
      boolean success,
      String errorMessage,
      long durationMs,
      Instant createdAt) {}
}
