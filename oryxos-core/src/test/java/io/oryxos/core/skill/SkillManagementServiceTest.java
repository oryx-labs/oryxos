package io.oryxos.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
import org.yaml.snakeyaml.Yaml;

class SkillManagementServiceTest {

  private static final String AGENT_NAME = "ops-agent";
  private static final String SECRET_BODY = "private prompt body must never be logged";

  @TempDir Path tempDir;

  private Path oryxosRoot;
  private Path agentsDir;
  private Path agentDir;
  private Path skillsDir;
  private Path importStagingDir;
  private ProfileRegistry profiles;
  private AgentSkillCatalog catalog;
  private SkillPackageImporter importer;
  private AgentSkillLockRegistry locks;
  private TestPublishOperations publishOperations;
  private SkillManagementService service;
  private ListAppender<ILoggingEvent> logs;

  @BeforeEach
  void setUp() throws IOException {
    oryxosRoot = Files.createDirectory(tempDir.resolve(".oryxos"));
    agentsDir = Files.createDirectory(oryxosRoot.resolve("agents"));
    agentDir = Files.createDirectory(agentsDir.resolve(AGENT_NAME));
    skillsDir = Files.createDirectory(agentDir.resolve("skills"));
    importStagingDir =
        Files.createDirectories(oryxosRoot.resolve(".staging").resolve("skill-import"));
    profiles = new ProfileRegistry(Map.of(AGENT_NAME, profile(AGENT_NAME)));
    catalog = mock(AgentSkillCatalog.class);
    importer = mock(SkillPackageImporter.class);
    locks = new AgentSkillLockRegistry();
    publishOperations = new TestPublishOperations();
    service =
        new SkillManagementService(
            agentsDir, profiles, catalog, importer, locks, publishOperations);

    logs = new ListAppender<>();
    logs.start();
    ((Logger) LoggerFactory.getLogger(SkillManagementService.class)).addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(SkillManagementService.class)).detachAppender(logs);
    logs.stop();
  }

  @Test
  void legalImportPublishesOnceReturnsActivePathsAndWritesOneSanitizedEvent() throws Exception {
    PreparedSkill prepared = prepared("weather");
    stubPrepared(prepared);

    SkillDescriptor imported =
        service.importSkill(AGENT_NAME, upload(), "C:\\private\\weather.zip");

    Path active = skillsDir.resolve("weather");
    assertTrue(Files.isDirectory(active));
    assertTrue(Files.isRegularFile(active.resolve("SKILL.md")));
    assertFalse(Files.exists(prepared.stagingEventDir()));
    assertEquals(SkillStatus.ENABLED, imported.status());
    assertEquals(SkillSource.UPLOAD, imported.source());
    assertEquals(active.resolve("SKILL.md").toAbsolutePath(), imported.metadata().entryPath());
    assertTrue(imported.catalogIncluded());
    assertEquals(1, publishOperations.moves);
    verify(catalog).validateCanImport(AGENT_NAME, imported.metadata());
    assertSingleManagementEvent("success", null, "weather");
    assertLogsAreSanitized();
  }

  @Test
  void nonexistentAgentRequestsDoNotAllocatePermanentLocksOrPrepareUploads() {
    assertThrows(NoSuchElementException.class, () -> service.list("missing-agent"));
    assertThrows(NoSuchElementException.class, () -> service.get("missing-agent", "weather"));
    assertThrows(
        NoSuchElementException.class,
        () -> service.importSkill("missing-agent", upload(), "weather.zip"));
    assertThrows(
        NoSuchElementException.class, () -> service.setEnabled("missing-agent", "weather", false));
    assertThrows(NoSuchElementException.class, () -> service.delete("missing-agent", "weather"));

    assertEquals(0, locks.registeredLockCount());
    verifyNoInteractions(catalog, importer);
  }

  @Test
  void agentArchivedDuringPrepareIsRejectedByTheWriteLockRecheckAndLeavesNoRemnants()
      throws Exception {
    PreparedSkill prepared = prepared("weather");
    Path archivedAgent = Files.createDirectories(oryxosRoot.resolve("archive")).resolve(AGENT_NAME);
    when(importer.prepare(any(InputStream.class), anyString()))
        .thenAnswer(
            ignored -> {
              profiles.remove(AGENT_NAME);
              Files.move(agentDir, archivedAgent);
              return prepared;
            });
    discardByDeletingEvent(prepared);

    assertThrows(
        NoSuchElementException.class,
        () -> service.importSkill(AGENT_NAME, upload(), "weather.zip"));

    assertFalse(Files.exists(agentsDir.resolve(AGENT_NAME)));
    assertFalse(Files.exists(prepared.stagingEventDir()));
    assertEquals(0, publishOperations.moves);
    verify(catalog, never()).validateCanImport(anyString(), any());
    assertSingleManagementEvent("rejected", "AGENT_NOT_FOUND", "weather");
  }

  @Test
  void enabledDisabledInvalidAndUnmanagedNamesAllConflictWithoutBeingOverwritten()
      throws Exception {
    Map<String, ExistingShape> existing =
        Map.of(
            "enabled", ExistingShape.ENABLED,
            "disabled", ExistingShape.DISABLED,
            "invalid", ExistingShape.INVALID,
            "unmanaged", ExistingShape.UNMANAGED);
    List<Path> stagingEvents = new ArrayList<>();
    for (Map.Entry<String, ExistingShape> entry : existing.entrySet()) {
      Path target = existing(entry.getKey(), entry.getValue());
      PreparedSkill prepared = prepared(entry.getKey());
      stagingEvents.add(prepared.stagingEventDir());
      stubPrepared(prepared);

      assertThrows(
          SkillConflictException.class,
          () -> service.importSkill(AGENT_NAME, upload(), entry.getKey() + ".zip"));

      assertTrue(Files.isDirectory(target));
      assertEquals(entry.getValue().sentinel, Files.readString(target.resolve("sentinel.txt")));
    }

    assertTrue(stagingEvents.stream().noneMatch(path -> Files.exists(path)));
    assertEquals(0, publishOperations.moves);
    assertEquals(4, managementEvents().size());
    assertTrue(
        managementEvents().stream()
            .allMatch(event -> "rejected".equals(keyValue(event, "result"))));
    assertTrue(
        managementEvents().stream()
            .allMatch(event -> "SKILL_CONFLICT".equals(keyValue(event, "reasonCode"))));
    verify(catalog, never()).validateCanImport(anyString(), any());
  }

  @Test
  void caseFoldedUnmanagedNameCreatedDuringPrepareIsCaughtByTheSecondConflictCheck()
      throws Exception {
    PreparedSkill prepared = prepared("weather");
    when(importer.prepare(any(InputStream.class), anyString()))
        .thenAnswer(
            ignored -> {
              Path raced = Files.createDirectory(skillsDir.resolve("Weather"));
              Files.writeString(raced.resolve("sentinel.txt"), "do-not-overwrite");
              return prepared;
            });
    discardByDeletingEvent(prepared);

    assertThrows(
        SkillConflictException.class,
        () -> service.importSkill(AGENT_NAME, upload(), "weather.zip"));

    try (Stream<Path> children = Files.list(skillsDir)) {
      assertFalse(
          children.anyMatch(path -> path.getFileName().toString().equals("weather")),
          "the lower-case target must not be created even on a case-insensitive filesystem");
    }
    assertEquals("do-not-overwrite", Files.readString(skillsDir.resolve("Weather/sentinel.txt")));
    assertFalse(Files.exists(prepared.stagingEventDir()));
    assertEquals(0, publishOperations.moves);
    assertSingleManagementEvent("rejected", "SKILL_CONFLICT", "weather");
  }

  @Test
  void l1BudgetFailureRejectsBeforeFileStoreCheckAndCleansStaging() throws Exception {
    PreparedSkill prepared = prepared("weather");
    stubPrepared(prepared);
    doThrow(
            new SkillValidationException(
                SkillValidationCode.CATALOG_BUDGET_EXCEEDED,
                "Agent Skill catalog exceeds the metadata budget"))
        .when(catalog)
        .validateCanImport(anyString(), any());

    assertThrows(
        SkillValidationException.class,
        () -> service.importSkill(AGENT_NAME, upload(), "weather.zip"));

    assertNoPublishedOrStaged(prepared);
    assertEquals(0, publishOperations.fileStoreChecks);
    assertEquals(0, publishOperations.moves);
    assertSingleManagementEvent("rejected", "CATALOG_BUDGET_EXCEEDED", "weather");
  }

  @Test
  void differentFileStoreFailsClosedWithoutCallingMoveAndCleansStaging() throws Exception {
    PreparedSkill prepared = prepared("weather");
    stubPrepared(prepared);
    publishOperations.sameFileStore = false;

    SkillManagementService.PublishException error =
        assertThrows(
            SkillManagementService.PublishException.class,
            () -> service.importSkill(AGENT_NAME, upload(), "weather.zip"));

    assertEquals("FILE_STORE_MISMATCH", error.reasonCode());
    assertNoPublishedOrStaged(prepared);
    assertEquals(1, publishOperations.fileStoreChecks);
    assertEquals(0, publishOperations.moves);
    assertSingleManagementEvent("failed", "FILE_STORE_MISMATCH", "weather");
  }

  @Test
  void atomicMoveFailureDoesNotFallBackOrLeakPathsAndLeavesNoRemnants() throws Exception {
    PreparedSkill prepared = prepared("weather");
    stubPrepared(prepared);
    publishOperations.moveFailure =
        new AtomicMoveNotSupportedException(
            prepared.packageRoot().toString(), "/private/active/weather", "secret provider detail");

    SkillManagementService.PublishException error =
        assertThrows(
            SkillManagementService.PublishException.class,
            () ->
                service.importSkill(
                    AGENT_NAME, upload(), "/Users/admin/Desktop/weather-secret.zip"));

    assertEquals("ATOMIC_PUBLISH_FAILED", error.reasonCode());
    assertNoPublishedOrStaged(prepared);
    assertEquals(1, publishOperations.moves);
    assertSingleManagementEvent("failed", "ATOMIC_PUBLISH_FAILED", "weather");
    assertLogsAreSanitized();
  }

  @Test
  void forgedPreparedPackageOutsideTrustedStagingIsRejectedBeforeBudgetOrMove() throws Exception {
    Path forgedEvent = Files.createDirectory(oryxosRoot.resolve(UUID.randomUUID().toString()));
    Path forgedPackage = Files.createDirectory(forgedEvent.resolve("weather"));
    Files.writeString(forgedPackage.resolve("SKILL.md"), SECRET_BODY);
    PreparedSkill prepared = prepared(forgedEvent, forgedPackage, "weather");
    stubPrepared(prepared);

    SkillManagementService.PublishException error =
        assertThrows(
            SkillManagementService.PublishException.class,
            () -> service.importSkill(AGENT_NAME, upload(), "weather.zip"));

    assertEquals("PREPARED_SKILL_INVALID", error.reasonCode());
    assertFalse(Files.exists(forgedEvent));
    assertFalse(Files.exists(skillsDir.resolve("weather")));
    verify(catalog, never()).validateCanImport(anyString(), any());
    assertSingleManagementEvent("failed", "PREPARED_SKILL_INVALID", "weather");
  }

  @Test
  void cleanupWarningAfterSuccessfulMoveDoesNotMisreportThePublishedMutation() throws Exception {
    PreparedSkill prepared = prepared("weather");
    when(importer.prepare(any(InputStream.class), anyString())).thenReturn(prepared);
    doAnswer(
            ignored -> {
              deleteTree(prepared.stagingEventDir());
              throw new IllegalStateException("/private/cleanup detail");
            })
        .when(importer)
        .discard(prepared);

    SkillDescriptor imported = service.importSkill(AGENT_NAME, upload(), "weather.zip");

    assertEquals(SkillStatus.ENABLED, imported.status());
    assertTrue(Files.exists(skillsDir.resolve("weather/SKILL.md")));
    assertFalse(Files.exists(prepared.stagingEventDir()));
    assertSingleManagementEvent("success", null, "weather");
    assertEquals(
        1,
        logs.list.stream()
            .filter(event -> "skill.staging.cleanup-failed".equals(keyValue(event, "event")))
            .count());
    assertLogsAreSanitized();
  }

  @Test
  void listHoldsAShortReadLockForTheWholeScanAndDoesNotEmitManagementLogs() throws Exception {
    CountDownLatch scanEntered = new CountDownLatch(1);
    CountDownLatch releaseScan = new CountDownLatch(1);
    when(catalog.list(AGENT_NAME))
        .thenAnswer(
            ignored -> {
              scanEntered.countDown();
              assertTrue(releaseScan.await(3, TimeUnit.SECONDS));
              return List.of();
            });
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicBoolean writerRan = new AtomicBoolean();
    Thread reader =
        Thread.ofPlatform()
            .unstarted(
                () -> {
                  try {
                    service.list(AGENT_NAME);
                  } catch (Throwable error) {
                    failure.set(error);
                  }
                });
    Thread writer =
        Thread.ofPlatform()
            .unstarted(
                () -> {
                  try {
                    locks.withWriteLock(
                        AGENT_NAME,
                        () -> {
                          writerRan.set(true);
                          return null;
                        });
                  } catch (Throwable error) {
                    failure.set(error);
                  }
                });

    reader.start();
    assertTrue(scanEntered.await(3, TimeUnit.SECONDS));
    writer.start();
    awaitQueued(writer);
    assertFalse(writerRan.get());
    releaseScan.countDown();
    reader.join(3_000);
    writer.join(3_000);

    assertFalse(reader.isAlive());
    assertFalse(writer.isAlive());
    assertNull(failure.get());
    assertTrue(writerRan.get());
    assertEquals(0, managementEvents().size());
  }

  @Test
  void toggleStateMachineUsesZeroByteMarkerAndKeepsEveryCallIdempotent() throws Exception {
    useRealCatalog(SkillLimits.defaults());
    Path weather = validSkill("weather", false, false);

    SkillDescriptor disabled = service.setEnabled(AGENT_NAME, "weather", false);
    SkillDescriptor disabledAgain = service.setEnabled(AGENT_NAME, "weather", false);

    Path marker = weather.resolve(".oryxos-disabled");
    assertEquals(SkillStatus.DISABLED, disabled.status());
    assertEquals(SkillStatus.DISABLED, disabledAgain.status());
    assertFalse(disabled.configuredEnabled());
    assertTrue(Files.isRegularFile(marker));
    assertEquals(0, Files.size(marker));

    SkillDescriptor enabled = service.setEnabled(AGENT_NAME, "weather", true);
    SkillDescriptor enabledAgain = service.setEnabled(AGENT_NAME, "weather", true);

    assertEquals(SkillStatus.ENABLED, enabled.status());
    assertEquals(SkillStatus.ENABLED, enabledAgain.status());
    assertTrue(enabled.configuredEnabled());
    assertFalse(Files.exists(marker));
    assertEquals(4, managementEvents().size());
    assertManagementEvent(0, "disable", "success", null, "weather");
    assertManagementEvent(1, "disable", "success", null, "weather");
    assertManagementEvent(2, "enable", "success", null, "weather");
    assertManagementEvent(3, "enable", "success", null, "weather");
  }

  @Test
  void invalidSkillMayBeDisabledButCannotBeEnabledUntilFullRevalidationPasses() throws Exception {
    useRealCatalog(SkillLimits.defaults());
    Path broken = Files.createDirectory(skillsDir.resolve("broken"));
    Files.writeString(broken.resolve("SKILL.md"), "not frontmatter\n" + SECRET_BODY);

    SkillDescriptor disabled = service.setEnabled(AGENT_NAME, "broken", false);

    Path marker = broken.resolve(".oryxos-disabled");
    assertEquals(SkillStatus.INVALID, disabled.status());
    assertFalse(disabled.configuredEnabled());
    assertEquals(0, Files.size(marker));
    SkillValidationException rejected =
        assertThrows(
            SkillValidationException.class, () -> service.setEnabled(AGENT_NAME, "broken", true));
    assertEquals(SkillValidationCode.MISSING_FRONTMATTER, rejected.code());
    assertTrue(Files.exists(marker));

    writeValidSkillMarkdown(broken, "broken");
    SkillDescriptor repaired = service.setEnabled(AGENT_NAME, "broken", true);

    assertEquals(SkillStatus.ENABLED, repaired.status());
    assertFalse(Files.exists(marker));
    assertManagementEvent(0, "disable", "success", null, "broken");
    assertManagementEvent(1, "enable", "rejected", "MISSING_FRONTMATTER", "broken");
    assertManagementEvent(2, "enable", "success", null, "broken");
  }

  @Test
  void enablingRejectsAnOverBudgetCatalogWithoutRemovingTheMarker() throws Exception {
    SkillLimits oneSkillOnly = withCatalogLimits(1, 12_000);
    useRealCatalog(oneSkillOnly);
    validSkill("alpha", false, false);
    Path beta = validSkill("beta", true, false);
    Path marker = beta.resolve(".oryxos-disabled");

    SkillValidationException error =
        assertThrows(
            SkillValidationException.class, () -> service.setEnabled(AGENT_NAME, "beta", true));

    assertEquals(SkillValidationCode.CATALOG_BUDGET_EXCEEDED, error.code());
    assertTrue(Files.exists(marker));
    assertEquals(0, Files.size(marker));
    assertEquals(SkillStatus.DISABLED, catalog.get(AGENT_NAME, "beta").status());
    assertSingleManagementEvent("enable", "rejected", "CATALOG_BUDGET_EXCEEDED", "beta");
  }

  @Test
  void managedSidecarsDoNotMakeAMaxEntryPackageImpossibleToReenable() throws Exception {
    useRealCatalog(withMaxEntries(1));
    Path weather = validSkill("weather", true, true);

    SkillDescriptor enabled = service.setEnabled(AGENT_NAME, "weather", true);

    assertEquals(SkillStatus.ENABLED, enabled.status());
    assertFalse(Files.exists(weather.resolve(".oryxos-disabled")));
  }

  @Test
  void toggleWaitsForTheReadLeaseBeforeChangingTheMarker() throws Exception {
    useRealCatalog(SkillLimits.defaults());
    Path weather = validSkill("weather", false, false);
    CountDownLatch readerEntered = new CountDownLatch(1);
    CountDownLatch releaseReader = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread reader =
        thread(
            "toggle-reader",
            failure,
            () ->
                locks.withReadLock(
                    AGENT_NAME,
                    () -> {
                      readerEntered.countDown();
                      assertTrue(releaseReader.await(3, TimeUnit.SECONDS));
                      return null;
                    }));
    Thread toggle =
        thread("toggle-writer", failure, () -> service.setEnabled(AGENT_NAME, "weather", false));

    reader.start();
    assertTrue(readerEntered.await(3, TimeUnit.SECONDS));
    toggle.start();
    awaitQueued(toggle);
    assertFalse(Files.exists(weather.resolve(".oryxos-disabled")));
    releaseReader.countDown();
    reader.join(3_000);
    toggle.join(3_000);

    assertNull(failure.get());
    assertTrue(Files.exists(weather.resolve(".oryxos-disabled")));
    assertSingleManagementEvent("disable", "success", null, "weather");
  }

  @Test
  void deleteArchivesEnabledDisabledAndInvalidPackagesWithSourceMetadata() throws Exception {
    useRealCatalog(SkillLimits.defaults());
    Path enabled = validSkill("enabled", false, false);
    Files.createDirectories(enabled.resolve("references"));
    Files.writeString(enabled.resolve("references/rules.md"), "archived rules");
    Path disabled = validSkill("disabled", true, true);
    Path invalid = Files.createDirectory(skillsDir.resolve("invalid"));
    Files.writeString(invalid.resolve("SKILL.md"), "broken");

    service.delete(AGENT_NAME, "enabled");
    service.delete(AGENT_NAME, "disabled");
    service.delete(AGENT_NAME, "invalid");

    assertFalse(Files.exists(enabled));
    assertFalse(Files.exists(disabled));
    assertFalse(Files.exists(invalid));
    Map<String, ArchivedPackageView> archived = archivedBySkill();
    assertEquals(3, archived.size());
    assertEquals("workspace", archived.get("enabled").metadata().get("source"));
    assertEquals("upload", archived.get("disabled").metadata().get("source"));
    assertEquals("workspace", archived.get("invalid").metadata().get("source"));
    assertEquals(
        "agents/ops-agent/skills/disabled",
        archived.get("disabled").metadata().get("originalRelativePath"));
    assertTrue(Files.exists(archived.get("disabled").packageDir().resolve(".oryxos-disabled")));
    assertTrue(Files.exists(archived.get("invalid").packageDir().resolve("SKILL.md")));
    assertEquals(
        "archived rules",
        Files.readString(archived.get("enabled").packageDir().resolve("references/rules.md")));
    assertTrue(
        archived.values().stream()
            .allMatch(
                view ->
                    view.eventDir()
                        .getFileName()
                        .toString()
                        .matches("[0-9]{8}T[0-9]{6}Z-[0-9a-f-]{36}")));
    assertManagementEvent(0, "delete", "success", null, "enabled");
    assertManagementEvent(1, "delete", "success", null, "disabled");
    assertManagementEvent(2, "delete", "success", null, "invalid");
  }

  @Test
  void deletingMissingOrUnmanagedSkillReturnsNotFoundWithoutCreatingArchiveEvent()
      throws Exception {
    useRealCatalog(SkillLimits.defaults());
    Files.createDirectory(skillsDir.resolve("unmanaged"));

    assertThrows(NoSuchElementException.class, () -> service.delete(AGENT_NAME, "missing"));
    assertThrows(NoSuchElementException.class, () -> service.delete(AGENT_NAME, "unmanaged"));

    assertTrue(Files.exists(skillsDir.resolve("unmanaged")));
    assertFalse(Files.exists(oryxosRoot.resolve("archive/.skills/ops-agent")));
    assertEquals(2, managementEvents().size());
    assertManagementEvent(0, "delete", "rejected", "NOT_FOUND", "unresolved");
    assertManagementEvent(1, "delete", "rejected", "NOT_FOUND", "unresolved");
  }

  @Test
  void deleteWaitsForTheReadLeaseBeforeMovingTheActivePackage() throws Exception {
    useRealCatalog(SkillLimits.defaults());
    Path weather = validSkill("weather", false, false);
    CountDownLatch readerEntered = new CountDownLatch(1);
    CountDownLatch releaseReader = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread reader =
        thread(
            "delete-reader",
            failure,
            () ->
                locks.withReadLock(
                    AGENT_NAME,
                    () -> {
                      readerEntered.countDown();
                      assertTrue(releaseReader.await(3, TimeUnit.SECONDS));
                      return null;
                    }));
    Thread delete = thread("delete-writer", failure, () -> service.delete(AGENT_NAME, "weather"));

    reader.start();
    assertTrue(readerEntered.await(3, TimeUnit.SECONDS));
    delete.start();
    awaitQueued(delete);
    assertTrue(Files.exists(weather));
    releaseReader.countDown();
    reader.join(3_000);
    delete.join(3_000);

    assertNull(failure.get());
    assertFalse(Files.exists(weather));
    assertEquals(1, archivedBySkill().size());
    assertSingleManagementEvent("delete", "success", null, "weather");
  }

  private void useRealCatalog(SkillLimits limits) {
    catalog =
        new AgentSkillCatalog(
            agentsDir, new SkillMetadataReader(), new SkillContentValidator(), limits);
    service =
        new SkillManagementService(
            agentsDir, profiles, catalog, importer, locks, publishOperations);
  }

  private Path validSkill(String name, boolean disabled, boolean uploaded) throws IOException {
    Path skillDir = Files.createDirectory(skillsDir.resolve(name));
    writeValidSkillMarkdown(skillDir, name);
    if (disabled) {
      Files.createFile(skillDir.resolve(".oryxos-disabled"));
    }
    if (uploaded) {
      Files.writeString(
          skillDir.resolve(".oryxos-origin.yml"),
          """
          schemaVersion: 1
          sourceType: upload
          originalFilename: source.zip
          importedAt: 2026-07-22T10:30:00Z
          """);
    }
    return skillDir;
  }

  private static void writeValidSkillMarkdown(Path skillDir, String name) throws IOException {
    Files.writeString(
        skillDir.resolve("SKILL.md"),
        """
        ---
        name: %s
        description: Managed test Skill
        ---

        # Instructions

        Execute the managed test workflow.
        """
            .formatted(name));
  }

  private SkillLimits withCatalogLimits(int maxSkills, int maxCatalogChars) {
    SkillLimits defaults = SkillLimits.defaults();
    return new SkillLimits(
        defaults.maxArchiveBytes(),
        defaults.maxExpandedBytes(),
        defaults.maxFileBytes(),
        defaults.maxSkillMarkdownBytes(),
        defaults.maxFrontmatterBytes(),
        defaults.maxYamlNestingDepth(),
        defaults.maxEntries(),
        defaults.maxDepth(),
        defaults.maxPathChars(),
        defaults.maxExpansionRatio(),
        maxSkills,
        defaults.maxCandidatesPerAgent(),
        maxCatalogChars,
        Duration.ofHours(24));
  }

  private SkillLimits withMaxEntries(int maxEntries) {
    SkillLimits defaults = SkillLimits.defaults();
    return new SkillLimits(
        defaults.maxArchiveBytes(),
        defaults.maxExpandedBytes(),
        defaults.maxFileBytes(),
        defaults.maxSkillMarkdownBytes(),
        defaults.maxFrontmatterBytes(),
        defaults.maxYamlNestingDepth(),
        maxEntries,
        defaults.maxDepth(),
        defaults.maxPathChars(),
        defaults.maxExpansionRatio(),
        defaults.maxSkillsPerAgent(),
        defaults.maxCandidatesPerAgent(),
        defaults.maxCatalogChars(),
        Duration.ofHours(24));
  }

  private Map<String, ArchivedPackageView> archivedBySkill() throws IOException {
    Path agentArchive = oryxosRoot.resolve("archive/.skills").resolve(AGENT_NAME);
    if (!Files.isDirectory(agentArchive)) {
      return Map.of();
    }
    Map<String, ArchivedPackageView> result = new HashMap<>();
    try (var events = Files.list(agentArchive)) {
      for (Path event : events.toList()) {
        Path metadataPath = event.resolve("archive.yml");
        Path packageDir = event.resolve("package");
        assertTrue(Files.isRegularFile(metadataPath));
        assertTrue(Files.isDirectory(packageDir));
        Object loaded = new Yaml().load(Files.readString(metadataPath));
        assertTrue(loaded instanceof Map<?, ?>);
        Map<String, Object> metadata = new HashMap<>();
        ((Map<?, ?>) loaded).forEach((key, value) -> metadata.put(String.valueOf(key), value));
        result.put(
            String.valueOf(metadata.get("skill")),
            new ArchivedPackageView(event, packageDir, Map.copyOf(metadata)));
      }
    }
    return Map.copyOf(result);
  }

  private PreparedSkill prepared(String name) throws IOException {
    Path event = Files.createDirectory(importStagingDir.resolve(UUID.randomUUID().toString()));
    Path unpacked = Files.createDirectory(event.resolve("unpacked"));
    Path packageRoot = Files.createDirectory(unpacked.resolve(name));
    Files.writeString(packageRoot.resolve("SKILL.md"), SECRET_BODY);
    return prepared(event, packageRoot, name);
  }

  private PreparedSkill prepared(Path event, Path packageRoot, String name) throws IOException {
    SkillMetadata metadata =
        new SkillMetadata(
            name,
            "Weather guidance",
            "Apache-2.0",
            null,
            Map.of("author", "test"),
            "read_file",
            packageRoot.resolve("SKILL.md").toAbsolutePath(),
            "skills/" + name + "/SKILL.md");
    SkillOrigin origin =
        new SkillOrigin(
            SkillOrigin.CURRENT_SCHEMA_VERSION,
            SkillSource.UPLOAD,
            name + ".zip",
            Instant.parse("2026-07-22T10:30:00Z"));
    long bytes = Files.size(packageRoot.resolve("SKILL.md"));
    return new PreparedSkill(
        event,
        packageRoot,
        name,
        metadata,
        origin,
        new SkillContentValidator.ContentStats(List.of("SKILL.md"), 1, bytes));
  }

  private void stubPrepared(PreparedSkill prepared) throws Exception {
    when(importer.prepare(any(InputStream.class), anyString())).thenReturn(prepared);
    discardByDeletingEvent(prepared);
  }

  private void discardByDeletingEvent(PreparedSkill prepared) throws Exception {
    doAnswer(
            ignored -> {
              deleteTree(prepared.stagingEventDir());
              return null;
            })
        .when(importer)
        .discard(prepared);
  }

  private Path existing(String name, ExistingShape shape) throws IOException {
    Path target = Files.createDirectory(skillsDir.resolve(name));
    Files.writeString(target.resolve("sentinel.txt"), shape.sentinel);
    switch (shape) {
      case ENABLED -> Files.writeString(target.resolve("SKILL.md"), "enabled");
      case DISABLED -> {
        Files.writeString(target.resolve("SKILL.md"), "disabled");
        Files.writeString(target.resolve(".oryxos-disabled"), "");
      }
      case INVALID -> Files.writeString(target.resolve(".oryxos-origin.yml"), "broken");
      case UNMANAGED -> {
        // Deliberately no SKILL.md or marker.
      }
    }
    return target;
  }

  private void assertNoPublishedOrStaged(PreparedSkill prepared) {
    assertFalse(Files.exists(skillsDir.resolve(prepared.directoryName())));
    assertFalse(Files.exists(prepared.stagingEventDir()));
  }

  private void assertSingleManagementEvent(
      String expectedResult, String expectedReason, String expectedSkill) {
    assertSingleManagementEvent("import", expectedResult, expectedReason, expectedSkill);
  }

  private void assertSingleManagementEvent(
      String expectedAction, String expectedResult, String expectedReason, String expectedSkill) {
    List<ILoggingEvent> events = managementEvents();
    assertEquals(1, events.size());
    assertManagementEvent(0, expectedAction, expectedResult, expectedReason, expectedSkill);
  }

  private void assertManagementEvent(
      int index,
      String expectedAction,
      String expectedResult,
      String expectedReason,
      String expectedSkill) {
    ILoggingEvent event = managementEvents().get(index);
    assertEquals(AGENT_NAME, keyValue(event, "agent"));
    assertEquals(expectedSkill, keyValue(event, "skill"));
    assertEquals(expectedAction, keyValue(event, "action"));
    assertEquals(expectedResult, keyValue(event, "result"));
    assertEquals(expectedReason, keyValue(event, "reasonCode"));
  }

  private List<ILoggingEvent> managementEvents() {
    return logs.list.stream()
        .filter(event -> "skill.management".equals(keyValue(event, "event")))
        .toList();
  }

  private void assertLogsAreSanitized() {
    String rendered =
        logs.list.stream()
            .map(
                event ->
                    event.getFormattedMessage() + " " + String.valueOf(event.getKeyValuePairs()))
            .reduce("", (left, right) -> left + " " + right);
    assertFalse(rendered.contains(SECRET_BODY));
    assertFalse(rendered.contains(tempDir.toString()));
    assertFalse(rendered.contains("/Users/"));
    assertFalse(rendered.contains("/private/"));
    assertFalse(rendered.contains("secret provider detail"));
    assertFalse(rendered.contains("cleanup detail"));
  }

  private static Object keyValue(ILoggingEvent event, String key) {
    List<KeyValuePair> pairs = event.getKeyValuePairs();
    if (pairs == null) {
      return null;
    }
    return pairs.stream()
        .filter(pair -> key.equals(pair.key))
        .map(pair -> pair.value)
        .findFirst()
        .orElse(null);
  }

  private static void deleteTree(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  private static InputStream upload() {
    return new ByteArrayInputStream("zip bytes".getBytes(StandardCharsets.UTF_8));
  }

  private static Profile profile(String name) {
    return new Profile(
        name,
        "test",
        null,
        new Profile.ProviderRef("mock", "mock", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Profile.Settings.defaults());
  }

  private static Thread thread(
      String name, AtomicReference<Throwable> failure, ThrowingRunnable work) {
    return Thread.ofPlatform()
        .name(name)
        .unstarted(
            () -> {
              try {
                work.run();
              } catch (Throwable error) {
                failure.compareAndSet(null, error);
              }
            });
  }

  private void awaitQueued(Thread thread) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!locks.hasQueuedThread(AGENT_NAME, thread) && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertTrue(locks.hasQueuedThread(AGENT_NAME, thread));
  }

  private enum ExistingShape {
    ENABLED("enabled-sentinel"),
    DISABLED("disabled-sentinel"),
    INVALID("invalid-sentinel"),
    UNMANAGED("unmanaged-sentinel");

    private final String sentinel;

    ExistingShape(String sentinel) {
      this.sentinel = sentinel;
    }
  }

  private record ArchivedPackageView(
      Path eventDir, Path packageDir, Map<String, Object> metadata) {}

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  private static final class TestPublishOperations
      implements SkillManagementService.PublishOperations {

    private boolean sameFileStore = true;
    private IOException moveFailure;
    private int fileStoreChecks;
    private int moves;

    @Override
    public boolean sameFileStore(Path source, Path targetParent) {
      fileStoreChecks++;
      return sameFileStore;
    }

    @Override
    public void atomicMove(Path source, Path target) throws IOException {
      moves++;
      if (moveFailure != null) {
        throw moveFailure;
      }
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }
  }
}
