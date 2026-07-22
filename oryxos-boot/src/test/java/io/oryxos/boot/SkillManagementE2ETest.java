package io.oryxos.boot;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.context.ContextLoader;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.session.Message;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import io.oryxos.core.skill.AgentSkillCatalog;
import io.oryxos.core.skill.AgentSkillCoordinator;
import io.oryxos.core.skill.AgentSkillLockRegistry;
import io.oryxos.core.skill.SkillContentValidator;
import io.oryxos.core.skill.SkillLease;
import io.oryxos.core.skill.SkillLimits;
import io.oryxos.core.skill.SkillManagementService;
import io.oryxos.core.skill.SkillMetadataReader;
import io.oryxos.core.skill.SkillPackageImporter;
import io.oryxos.core.skill.SkillSnapshot;
import io.oryxos.storage.LlmCall;
import io.oryxos.storage.LlmCallRepository;
import io.oryxos.storage.SessionRepository;
import io.oryxos.storage.ToolInvocation;
import io.oryxos.storage.ToolInvocationRepository;
import io.oryxos.web.GlobalExceptionHandler;
import io.oryxos.web.controller.AgentSkillApiController;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** End-to-end REST proof for safe import, immediate management reads and next-request discovery. */
class SkillManagementE2ETest {

  private static final String AGENT = "ops-agent";
  private static final String BODY_MARKER = "WEATHER_IMPORT_BODY_MUST_STAY_L2";

  @TempDir Path tempDir;

  private Path oryxosRoot;
  private Path skillsDir;
  private MockMvc mvc;
  private AgentSkillCoordinator coordinator;
  private SkillManagementService management;
  private Profile profile;

  @BeforeEach
  void setUp() throws Exception {
    oryxosRoot = Files.createDirectories(tempDir.resolve(".oryxos"));
    Path agentDir = Files.createDirectories(oryxosRoot.resolve("agents").resolve(AGENT));
    skillsDir = Files.createDirectory(agentDir.resolve("skills"));
    Files.writeString(
        agentDir.resolve("AGENT.md"),
        "---\nname: ops-agent\n---\nUse matching private Skills only when relevant.\n");
    profile = profile();
    ProfileRegistry profiles = new ProfileRegistry(Map.of(AGENT, profile));
    SkillLimits limits = SkillLimits.defaults();
    SkillMetadataReader metadataReader = new SkillMetadataReader();
    SkillContentValidator contentValidator = new SkillContentValidator();
    AgentSkillCatalog catalog =
        new AgentSkillCatalog(
            oryxosRoot.resolve("agents"), metadataReader, contentValidator, limits);
    AgentSkillLockRegistry locks = new AgentSkillLockRegistry();
    coordinator = new AgentSkillCoordinator(oryxosRoot.resolve("agents"), profiles, catalog, locks);
    SkillPackageImporter importer = new SkillPackageImporter(oryxosRoot, limits);
    management =
        new SkillManagementService(
            oryxosRoot.resolve("agents"), profiles, catalog, importer, locks);
    mvc =
        MockMvcBuilders.standaloneSetup(new AgentSkillApiController(management))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void uploadIsImmediatelyManageableAndOnlyTheNextRequestReceivesItsL1() throws Exception {
    byte[] archive = validWeatherArchive();
    SkillSnapshot before;

    try (SkillLease lease = coordinator.openRequest(AGENT)) {
      before = lease.snapshot();
    }
    assertTrue(before.skills().isEmpty());

    mvc.perform(
            multipart("/api/v1/agents/{agentName}/skills", AGENT)
                .file(new MockMultipartFile("file", "weather.zip", "application/zip", archive)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("weather"))
        .andExpect(jsonPath("$.data.status").value("enabled"))
        .andExpect(jsonPath("$.data.source").value("upload"))
        .andExpect(jsonPath("$.data.entrypoint").value("skills/weather/SKILL.md"))
        .andExpect(content().string(not(containsString(BODY_MARKER))));

    assertTrue(
        before.skills().isEmpty(),
        "an already-frozen request snapshot must not be rewritten by import");

    mvc.perform(get("/api/v1/agents/{agentName}/skills", AGENT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].directoryName").value("weather"));
    mvc.perform(get("/api/v1/agents/{agentName}/skills/{skillName}", AGENT, "weather"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.resources[0]").value("SKILL.md"))
        .andExpect(jsonPath("$.data.resources[1]").value("references/rules.md"))
        .andExpect(content().string(not(containsString(BODY_MARKER))));

    try (SkillLease next = coordinator.openRequest(AGENT)) {
      assertEquals(
          List.of("weather"), next.snapshot().skills().stream().map(s -> s.name()).toList());
      String l1 = new ContextLoader(oryxosRoot).load(profile, next.snapshot());
      assertTrue(l1.contains("- name: weather"));
      assertTrue(l1.contains(skillsDir.resolve("weather/SKILL.md").toString()));
      assertFalse(l1.contains(BODY_MARKER));
    }
  }

  @Test
  void invalidAndDuplicateArchivesLeaveThePublishedPackageAndStagingUnchanged() throws Exception {
    byte[] archive = validWeatherArchive();
    MockMultipartFile valid =
        new MockMultipartFile("file", "weather.zip", "application/zip", archive);
    mvc.perform(multipart("/api/v1/agents/{agentName}/skills", AGENT).file(valid))
        .andExpect(status().isOk());
    Path entry = skillsDir.resolve("weather/SKILL.md");
    String published = Files.readString(entry);

    mvc.perform(
            multipart("/api/v1/agents/{agentName}/skills", AGENT)
                .file(
                    new MockMultipartFile(
                        "file", "not-a-zip.zip", "application/zip", new byte[] {1, 2, 3})))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(not(containsString(oryxosRoot.toString()))));
    mvc.perform(
            multipart("/api/v1/agents/{agentName}/skills", AGENT)
                .file(
                    new MockMultipartFile("file", "weather-again.zip", "application/zip", archive)))
        .andExpect(status().isConflict())
        .andExpect(content().string(not(containsString(oryxosRoot.toString()))));

    assertEquals(published, Files.readString(entry));
    assertEquals(List.of("weather"), managedDirectoryNames());
    assertStagingHasNoEvents();
  }

  @Test
  void restLifecycleChangesOnlyFutureSnapshotsAndDeleteLeavesOneCompleteArchive() throws Exception {
    mvc.perform(
            multipart("/api/v1/agents/{agentName}/skills", AGENT)
                .file(
                    new MockMultipartFile(
                        "file", "weather.zip", "application/zip", validWeatherArchive())))
        .andExpect(status().isOk());

    SkillSnapshot frozen;
    try (SkillLease lease = coordinator.openRequest(AGENT)) {
      frozen = lease.snapshot();
    }
    assertEquals(List.of("weather"), skillNames(frozen));

    mvc.perform(
            put("/api/v1/agents/{agentName}/skills/{skillName}", AGENT, "weather")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("disabled"));
    assertEquals(List.of("weather"), skillNames(frozen), "a frozen request is immutable");
    assertNextRequestHasSkills(List.of());

    mvc.perform(
            put("/api/v1/agents/{agentName}/skills/{skillName}", AGENT, "weather")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("enabled"));
    assertNextRequestHasSkills(List.of("weather"));

    mvc.perform(delete("/api/v1/agents/{agentName}/skills/{skillName}", AGENT, "weather"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.timestamp").isNumber());
    assertNextRequestHasSkills(List.of());
    assertTrue(managedDirectoryNames().isEmpty());
    mvc.perform(get("/api/v1/agents/{agentName}/skills", AGENT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));
    mvc.perform(get("/api/v1/agents/{agentName}/skills/{skillName}", AGENT, "weather"))
        .andExpect(status().isNotFound());

    Path archiveAgent = oryxosRoot.resolve("archive/.skills").resolve(AGENT);
    List<Path> events;
    try (var children = Files.list(archiveAgent)) {
      events = children.toList();
    }
    assertEquals(1, events.size());
    Path event = events.get(0);
    String metadata = Files.readString(event.resolve("archive.yml"));
    assertTrue(metadata.contains("skill: weather"));
    assertTrue(metadata.contains("source: upload"));
    assertTrue(Files.isRegularFile(event.resolve("package/SKILL.md")));
    assertTrue(Files.isRegularFile(event.resolve("package/references/rules.md")));
    assertTrue(Files.isRegularFile(event.resolve("package/.oryxos-origin.yml")));

    mvc.perform(delete("/api/v1/agents/{agentName}/skills/{skillName}", AGENT, "weather"))
        .andExpect(status().isNotFound());
    try (var children = Files.list(archiveAgent)) {
      assertEquals(1, children.count(), "404 must not create a second archive event");
    }
  }

  @Test
  @Tag("integration")
  void bootedHttpLifecyclePreservesExistingSessionAndAuditRows() throws Exception {
    Path runtimeRoot = seedBootWorkspace(tempDir.resolve("boot-runtime"));
    String databaseUrl = "jdbc:sqlite:" + runtimeRoot.resolve("skill-lifecycle.db");

    try (ConfigurableApplicationContext context = bootWeb(runtimeRoot, databaseUrl)) {
      HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
      ObjectMapper json = new ObjectMapper();
      int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
      String endpoint = "http://127.0.0.1:" + port + "/api/v1/agents/" + AGENT + "/skills";

      SessionManager sessions = context.getBean(SessionManager.class);
      Session oldSession = sessions.getOrCreate("web", "before-skill-management", AGENT);
      String reply =
          context
              .getBean(AgentService.class)
              .process(oldSession, "remember this preexisting history");
      assertFalse(reply.isBlank());

      String oldSessionId = oldSession.sessionId();
      seedHistoricalFileAndShellAudits(context, oldSessionId);
      List<Message> savedHistory = sessions.get(oldSessionId).orElseThrow().messages();
      SessionRow savedSession = sessionRow(context, oldSessionId);
      List<ToolAuditRow> savedToolAudits = toolAuditRows(context, oldSessionId);
      List<LlmAuditRow> savedLlmAudits = llmAuditRows(context, oldSessionId);
      List<ToolAuditRow> savedFileAndShellAudits = fileAndShellRows(toolAuditRows(context));
      assertEquals(
          List.of("save_memory", "read_file", "shell"),
          savedToolAudits.stream().map(ToolAuditRow::toolName).toList());
      assertEquals(2, savedFileAndShellAudits.size());

      SkillSnapshot frozenBeforeImport = requestSnapshot(context);
      assertTrue(frozenBeforeImport.skills().isEmpty());

      List<ToolAuditRow> beforeImportAudits = toolAuditRows(context);
      JsonNode imported = postMultipart(http, json, endpoint, validWeatherArchive());
      assertEquals(beforeImportAudits, toolAuditRows(context), "import must not execute a Tool");
      assertEquals("weather", imported.get("name").asText());
      assertEquals("enabled", imported.get("status").asText());
      assertTrue(frozenBeforeImport.skills().isEmpty(), "an old request snapshot stays frozen");
      assertBootRequestSkills(context, List.of("weather"));
      runFreshAgentRequest(context, sessions, "after-import");

      JsonNode detail = getJson(http, json, endpoint + "/weather");
      assertEquals("skills/weather/SKILL.md", detail.get("entrypoint").asText());
      assertEquals("SKILL.md", detail.get("resources").get(0).asText());
      assertEquals("references/rules.md", detail.get("resources").get(1).asText());

      List<ToolAuditRow> beforeDisableAudits = toolAuditRows(context);
      JsonNode disabled = putJson(http, json, endpoint + "/weather", "{\"enabled\":false}");
      assertEquals(beforeDisableAudits, toolAuditRows(context), "disable must not execute a Tool");
      assertEquals("disabled", disabled.get("status").asText());
      assertBootRequestSkills(context, List.of());
      runFreshAgentRequest(context, sessions, "after-disable");

      List<ToolAuditRow> beforeEnableAudits = toolAuditRows(context);
      JsonNode enabled = putJson(http, json, endpoint + "/weather", "{\"enabled\":true}");
      assertEquals(beforeEnableAudits, toolAuditRows(context), "enable must not execute a Tool");
      assertEquals("enabled", enabled.get("status").asText());
      assertBootRequestSkills(context, List.of("weather"));
      runFreshAgentRequest(context, sessions, "after-enable");

      List<ToolAuditRow> beforeDeleteAudits = toolAuditRows(context);
      assertTrue(deleteJson(http, json, endpoint + "/weather").isNull());
      assertEquals(beforeDeleteAudits, toolAuditRows(context), "delete must not execute a Tool");
      assertBootRequestSkills(context, List.of());
      runFreshAgentRequest(context, sessions, "after-delete");
      assertEquals(0, getJson(http, json, endpoint).size());

      Path archiveAgent = runtimeRoot.resolve("archive/.skills").resolve(AGENT);
      List<Path> events;
      try (var children = Files.list(archiveAgent)) {
        events = children.toList();
      }
      assertEquals(1, events.size());
      assertTrue(Files.isRegularFile(events.get(0).resolve("package/SKILL.md")));
      assertTrue(
          Files.isRegularFile(events.get(0).resolve("package/references/rules.md")),
          "delete must archive the complete L3 package");

      assertEquals(savedHistory, sessions.get(oldSessionId).orElseThrow().messages());
      assertEquals(savedSession, sessionRow(context, oldSessionId));
      assertEquals(savedToolAudits, toolAuditRows(context, oldSessionId));
      assertEquals(savedLlmAudits, llmAuditRows(context, oldSessionId));
      assertEquals(savedFileAndShellAudits, fileAndShellRows(toolAuditRows(context)));
    }
  }

  @Test
  void startupCleanupRemovesExpiredIncompleteArchiveButNeverCompletedPackage() throws Exception {
    Path archiveAgent =
        Files.createDirectories(oryxosRoot.resolve("archive/.skills").resolve(AGENT));
    Path incomplete =
        Files.createDirectory(
            archiveAgent.resolve("20200101T000000Z-11111111-1111-4111-8111-111111111111"));
    Files.writeString(incomplete.resolve("archive.yml"), "partial: true\n");
    Path complete =
        Files.createDirectory(
            archiveAgent.resolve("20200101T000001Z-22222222-2222-4222-8222-222222222222"));
    Files.writeString(complete.resolve("archive.yml"), "schemaVersion: 1\n");
    Files.createDirectory(complete.resolve("package"));
    FileTime expired = FileTime.from(Instant.now().minus(Duration.ofHours(25)));
    Files.setLastModifiedTime(incomplete, expired);
    Files.setLastModifiedTime(complete, expired);

    management.cleanupArchiveOrphans(Duration.ofHours(24));

    assertFalse(Files.exists(incomplete));
    assertTrue(Files.isDirectory(complete.resolve("package")));
  }

  private void assertNextRequestHasSkills(List<String> expected) {
    try (SkillLease lease = coordinator.openRequest(AGENT)) {
      assertEquals(expected, skillNames(lease.snapshot()));
      String l1 = new ContextLoader(oryxosRoot).load(profile, lease.snapshot());
      assertEquals(expected.contains("weather"), l1.contains("- name: weather"));
      assertFalse(l1.contains(BODY_MARKER), "L1 must never inline the Skill body");
    }
  }

  private static List<String> skillNames(SkillSnapshot snapshot) {
    return snapshot.skills().stream().map(skill -> skill.name()).toList();
  }

  private List<String> managedDirectoryNames() throws Exception {
    try (var children = Files.list(skillsDir)) {
      return children.map(path -> path.getFileName().toString()).sorted().toList();
    }
  }

  private void assertStagingHasNoEvents() throws Exception {
    Path staging = oryxosRoot.resolve(".staging/skill-import");
    if (!Files.exists(staging)) {
      return;
    }
    try (var events = Files.list(staging)) {
      assertTrue(events.findAny().isEmpty(), "staging events must be cleaned on every branch");
    }
  }

  private static ConfigurableApplicationContext bootWeb(Path root, String databaseUrl) {
    System.setProperty("nacos.logging.default.config.enabled", "false");
    return new SpringApplicationBuilder(OryxOsRuntime.class)
        .web(WebApplicationType.SERVLET)
        .run(
            "--server.address=127.0.0.1",
            "--server.port=0",
            "--oryxos.root=" + root,
            "--oryxos.providers[0].name=mock",
            "--oryxos.author.provider=mock",
            "--oryxos.author.model=mock-model",
            "--spring.datasource.url=" + databaseUrl,
            "--spring.lifecycle.timeout-per-shutdown-phase=1s",
            "--spring.main.banner-mode=off",
            "--logging.level.root=WARN");
  }

  private static Path seedBootWorkspace(Path root) throws Exception {
    Files.createDirectories(root.resolve("memory"));
    Path agent = Files.createDirectories(root.resolve("agents").resolve(AGENT));
    Files.writeString(
        agent.resolve("AGENT.md"),
        """
        ---
        name: ops-agent
        description: Booted Skill management integration Agent
        identity:
          agent_name: Ops
          prompt: Keep existing history stable while private Skills are managed.
        provider:
          name: mock
          model: mock-model
        tools:
          - save_memory
        settings:
          max_iterations: 10
          max_history_turns: 20
        ---
        Use matching private Skills only when relevant.
        """);
    return root;
  }

  private static SkillSnapshot requestSnapshot(ConfigurableApplicationContext context) {
    AgentSkillCoordinator runtimeCoordinator = context.getBean(AgentSkillCoordinator.class);
    try (SkillLease request = runtimeCoordinator.openRequest(AGENT)) {
      return request.snapshot();
    }
  }

  private static void assertBootRequestSkills(
      ConfigurableApplicationContext context, List<String> expected) {
    SkillSnapshot snapshot = requestSnapshot(context);
    assertEquals(expected, skillNames(snapshot));
    Profile runtimeProfile = context.getBean(ProfileRegistry.class).get(AGENT).orElseThrow();
    String l1 = context.getBean(ContextLoader.class).load(runtimeProfile, snapshot);
    assertEquals(expected.contains("weather"), l1.contains("- name: weather"));
    assertFalse(l1.contains(BODY_MARKER), "booted next-request L1 must not inline the body");
  }

  private static void runFreshAgentRequest(
      ConfigurableApplicationContext context, SessionManager sessions, String userId) {
    Session fresh = sessions.getOrCreate("web", userId, AGENT);
    String reply =
        context.getBean(AgentService.class).process(fresh, "fresh request for " + userId);
    assertFalse(reply.isBlank());
    assertFalse(sessions.get(fresh.sessionId()).orElseThrow().messages().isEmpty());
  }

  private static void seedHistoricalFileAndShellAudits(
      ConfigurableApplicationContext context, String sessionId) {
    ToolInvocationRepository repository = context.getBean(ToolInvocationRepository.class);

    ToolInvocation readFile = new ToolInvocation();
    readFile.setSessionId(sessionId);
    readFile.setToolName("read_file");
    readFile.setInputJson("{\"path\":\"skills/legacy/SKILL.md\"}");
    readFile.setResultJson("historical skill body");
    readFile.setSuccess(true);
    readFile.setDurationMs(11);
    repository.save(readFile);

    ToolInvocation shell = new ToolInvocation();
    shell.setSessionId(sessionId);
    shell.setToolName("shell");
    shell.setInputJson("{\"command\":\"legacy-script\"}");
    shell.setSuccess(false);
    shell.setErrorMessage("historical sandbox rejection");
    shell.setDurationMs(12);
    repository.save(shell);
    repository.flush();
  }

  private static SessionRow sessionRow(ConfigurableApplicationContext context, String sessionId) {
    io.oryxos.storage.Session row =
        context.getBean(SessionRepository.class).findById(sessionId).orElseThrow();
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
    ToolInvocationRepository repository = context.getBean(ToolInvocationRepository.class);
    repository.flush();
    return repository.findAll().stream()
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

  private static List<ToolAuditRow> toolAuditRows(
      ConfigurableApplicationContext context, String sessionId) {
    return toolAuditRows(context).stream()
        .filter(row -> sessionId.equals(row.sessionId()))
        .toList();
  }

  private static List<LlmAuditRow> llmAuditRows(ConfigurableApplicationContext context) {
    LlmCallRepository repository = context.getBean(LlmCallRepository.class);
    repository.flush();
    return repository.findAll().stream()
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

  private static List<LlmAuditRow> llmAuditRows(
      ConfigurableApplicationContext context, String sessionId) {
    return llmAuditRows(context).stream().filter(row -> sessionId.equals(row.sessionId())).toList();
  }

  private static List<ToolAuditRow> fileAndShellRows(List<ToolAuditRow> rows) {
    return rows.stream()
        .filter(row -> "read_file".equals(row.toolName()) || "shell".equals(row.toolName()))
        .toList();
  }

  private static JsonNode postMultipart(
      HttpClient http, ObjectMapper json, String endpoint, byte[] archive) throws Exception {
    String boundary = "----oryxos-skill-" + UUID.randomUUID();
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.write(
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\";"
                + " filename=\"weather.zip\"\r\nContent-Type: application/zip\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    body.write(archive);
    body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
            .build();
    return responseData(http.send(request, HttpResponse.BodyHandlers.ofString()), json);
  }

  private static JsonNode getJson(HttpClient http, ObjectMapper json, String endpoint)
      throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(10)).GET().build();
    return responseData(http.send(request, HttpResponse.BodyHandlers.ofString()), json);
  }

  private static JsonNode putJson(
      HttpClient http, ObjectMapper json, String endpoint, String content) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(content))
            .build();
    return responseData(http.send(request, HttpResponse.BodyHandlers.ofString()), json);
  }

  private static JsonNode deleteJson(HttpClient http, ObjectMapper json, String endpoint)
      throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(10))
            .DELETE()
            .build();
    return responseData(http.send(request, HttpResponse.BodyHandlers.ofString()), json);
  }

  private static JsonNode responseData(HttpResponse<String> response, ObjectMapper json)
      throws Exception {
    assertEquals(200, response.statusCode(), response.body());
    JsonNode envelope = json.readTree(response.body());
    assertEquals(0, envelope.get("code").asInt(), response.body());
    return envelope.get("data");
  }

  private static byte[] validWeatherArchive() throws Exception {
    String skill =
        "---\n"
            + "name: weather\n"
            + "description: Weather and travel guidance\n"
            + "license: Apache-2.0\n"
            + "---\n"
            + BODY_MARKER
            + "\nRead references/rules.md only when needed.\n";
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
      putEntry(zip, "weather/SKILL.md", skill);
      putEntry(zip, "weather/references/rules.md", "WEATHER_IMPORT_REFERENCE_L3");
    }
    return bytes.toByteArray();
  }

  private static void putEntry(ZipOutputStream zip, String name, String content) throws Exception {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(content.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  private static Profile profile() {
    return new Profile(
        AGENT,
        "Skill management E2E",
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
