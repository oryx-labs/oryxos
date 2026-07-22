package io.oryxos.core.skill;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
import org.yaml.snakeyaml.Yaml;

class SkillArchiveSecurityTest {

  private static final String AGENT_NAME = "ops-agent";
  private static final UUID EVENT_ID = UUID.fromString("12345678-1234-4234-8234-123456789abc");
  private static final Instant DELETED_AT = Instant.parse("2026-07-22T11:00:00Z");

  @TempDir Path tempDir;

  private Path oryxosRoot;
  private Path agentsDir;
  private Path skillsDir;
  private AgentSkillCatalog catalog;
  private TestPublishOperations publishOperations;
  private TestArchiveOperations archiveOperations;
  private SkillManagementService service;
  private ListAppender<ILoggingEvent> logs;

  @BeforeEach
  void setUp() throws IOException {
    oryxosRoot = Files.createDirectory(tempDir.resolve(".oryxos"));
    agentsDir = Files.createDirectory(oryxosRoot.resolve("agents"));
    Path agentDir = Files.createDirectory(agentsDir.resolve(AGENT_NAME));
    skillsDir = Files.createDirectory(agentDir.resolve("skills"));
    ProfileRegistry profiles = new ProfileRegistry(Map.of(AGENT_NAME, profile(AGENT_NAME)));
    catalog =
        new AgentSkillCatalog(
            agentsDir,
            new SkillMetadataReader(),
            new SkillContentValidator(),
            SkillLimits.defaults());
    publishOperations = new TestPublishOperations();
    archiveOperations = new TestArchiveOperations();
    service =
        new SkillManagementService(
            agentsDir,
            profiles,
            catalog,
            mock(SkillPackageImporter.class),
            new AgentSkillLockRegistry(),
            publishOperations,
            archiveOperations,
            Clock.fixed(DELETED_AT, ZoneOffset.UTC),
            () -> EVENT_ID);

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
  void archivedSkillUsesUtcUuidEventNameAndFixedFieldYamlThatCannotBeInjected() {
    String injectedSkill = "weather\ninjected: true";
    ArchivedSkill archived =
        ArchivedSkill.create(AGENT_NAME, injectedSkill, SkillSource.WORKSPACE, DELETED_AT);

    String eventName = archived.eventDirectoryName(EVENT_ID);
    Object loaded = new Yaml().load(new String(archived.toYamlBytes(), StandardCharsets.UTF_8));

    assertEquals("20260722T110000Z-" + EVENT_ID, eventName);
    assertTrue(loaded instanceof Map<?, ?>);
    Map<?, ?> metadata = (Map<?, ?>) loaded;
    assertEquals(
        List.of("schemaVersion", "agent", "skill", "source", "deletedAt", "originalRelativePath"),
        metadata.keySet().stream().map(String::valueOf).toList());
    assertEquals(injectedSkill, metadata.get("skill"));
    assertFalse(metadata.containsKey("injected"));
    assertEquals("agents/ops-agent/skills/" + injectedSkill, metadata.get("originalRelativePath"));
  }

  @Test
  void archiveRootSymlinkIsRejectedWithoutTouchingActiveOrExternalDirectory() throws Exception {
    Path active = validSkill("weather");
    Path outside = Files.createDirectory(tempDir.resolve("outside-archive"));
    Files.createSymbolicLink(oryxosRoot.resolve("archive"), outside);

    SkillManagementService.PublishException error =
        assertThrows(
            SkillManagementService.PublishException.class,
            () -> service.delete(AGENT_NAME, "weather"));

    assertEquals("UNSAFE_ARCHIVE_ROOT", error.reasonCode());
    assertTrue(Files.exists(active));
    assertEquals(0, childCount(outside));
    assertSingleFailedAudit("UNSAFE_ARCHIVE_ROOT");
  }

  @Test
  void archiveAgentSymlinkCannotRedirectPackageOutsideTheWorkspace() throws Exception {
    Path active = validSkill("weather");
    Path agentArchiveParent = Files.createDirectories(oryxosRoot.resolve("archive/.skills"));
    Path outside = Files.createDirectory(tempDir.resolve("outside-agent-archive"));
    Files.createSymbolicLink(agentArchiveParent.resolve(AGENT_NAME), outside);

    SkillManagementService.PublishException error =
        assertThrows(
            SkillManagementService.PublishException.class,
            () -> service.delete(AGENT_NAME, "weather"));

    assertEquals("UNSAFE_ARCHIVE_AGENT", error.reasonCode());
    assertTrue(Files.exists(active));
    assertEquals(0, childCount(outside));
    assertSingleFailedAudit("UNSAFE_ARCHIVE_AGENT");
  }

  @Test
  void differentFileStoreRemovesTheIncompleteEventAndKeepsActivePackage() throws Exception {
    Path active = validSkill("weather");
    publishOperations.sameFileStore = false;

    SkillManagementService.PublishException error =
        assertThrows(
            SkillManagementService.PublishException.class,
            () -> service.delete(AGENT_NAME, "weather"));

    assertEquals("FILE_STORE_MISMATCH", error.reasonCode());
    assertTrue(Files.exists(active));
    assertNoArchiveEvents();
    assertEquals(0, publishOperations.moves);
    assertSingleFailedAudit("FILE_STORE_MISMATCH");
  }

  @Test
  void metadataWriteFailureCleansPartialFileAndKeepsActivePackage() throws Exception {
    Path active = validSkill("weather");
    archiveOperations.failMetadataAfterPartialWrite = true;

    SkillManagementService.PublishException error =
        assertThrows(
            SkillManagementService.PublishException.class,
            () -> service.delete(AGENT_NAME, "weather"));

    assertEquals("ARCHIVE_METADATA_WRITE_FAILED", error.reasonCode());
    assertTrue(Files.exists(active));
    assertNoArchiveEvents();
    assertEquals(0, publishOperations.moves);
    assertSingleFailedAudit("ARCHIVE_METADATA_WRITE_FAILED");
  }

  @Test
  void atomicMoveFailureDoesNotFallBackAndRemovesIncompleteEvent() throws Exception {
    Path active = validSkill("weather");
    publishOperations.moveFailure =
        new AtomicMoveNotSupportedException(
            active.toString(), "/private/archive/package", "provider detail");

    SkillManagementService.PublishException error =
        assertThrows(
            SkillManagementService.PublishException.class,
            () -> service.delete(AGENT_NAME, "weather"));

    assertEquals("ATOMIC_ARCHIVE_FAILED", error.reasonCode());
    assertTrue(Files.exists(active));
    assertNoArchiveEvents();
    assertEquals(1, publishOperations.moves);
    assertSingleFailedAudit("ATOMIC_ARCHIVE_FAILED");
    String rendered =
        logs.list.stream()
            .map(event -> event.getFormattedMessage() + event.getKeyValuePairs())
            .reduce("", String::concat);
    assertFalse(rendered.contains(tempDir.toString()));
    assertFalse(rendered.contains("/private/"));
    assertFalse(rendered.contains("provider detail"));
  }

  @Test
  void startupCleanupRemovesOnlyExpiredIncompleteEvents() throws Exception {
    Path agentArchive = Files.createDirectories(oryxosRoot.resolve("archive/.skills/ops-agent"));
    Path expiredIncomplete =
        Files.createDirectory(
            agentArchive.resolve("20260721T100000Z-11111111-1111-4111-8111-111111111111"));
    Files.writeString(expiredIncomplete.resolve("archive.yml"), "partial: true");
    Path expiredComplete =
        Files.createDirectory(
            agentArchive.resolve("20260721T100001Z-22222222-2222-4222-8222-222222222222"));
    Files.writeString(expiredComplete.resolve("archive.yml"), "schemaVersion: 1");
    Files.createDirectory(expiredComplete.resolve("package"));
    Path freshIncomplete =
        Files.createDirectory(
            agentArchive.resolve("20260722T100000Z-33333333-3333-4333-8333-333333333333"));
    Files.writeString(freshIncomplete.resolve("archive.yml"), "partial: true");

    FileTime expired = FileTime.from(DELETED_AT.minus(Duration.ofHours(25)));
    Files.setLastModifiedTime(expiredIncomplete, expired);
    Files.setLastModifiedTime(expiredComplete, expired);
    Files.setLastModifiedTime(
        freshIncomplete, FileTime.from(DELETED_AT.minus(Duration.ofHours(23))));

    service.cleanupArchiveOrphans(Duration.ofHours(24));

    assertFalse(Files.exists(expiredIncomplete));
    assertTrue(Files.isDirectory(expiredComplete.resolve("package")));
    assertTrue(Files.isRegularFile(expiredComplete.resolve("archive.yml")));
    assertTrue(Files.isDirectory(freshIncomplete));
    assertTrue(
        logs.list.stream().noneMatch(event -> "skill.management".equals(keyValue(event, "event"))));
  }

  @Test
  void startupCleanupSkipsMalformedArchiveAgentChildren() throws Exception {
    Path archiveSkills = Files.createDirectories(oryxosRoot.resolve("archive/.skills"));
    Path malformedAgent =
        Files.writeString(archiveSkills.resolve("stale-agent"), "not a directory");

    assertDoesNotThrow(() -> service.cleanupArchiveOrphans(Duration.ofHours(24)));

    assertTrue(Files.isRegularFile(malformedAgent));
    assertTrue(
        logs.list.stream()
            .anyMatch(
                event -> "skill.archive.cleanup-unsafe-entry".equals(keyValue(event, "event"))));
  }

  private Path validSkill(String name) throws IOException {
    Path skill = Files.createDirectory(skillsDir.resolve(name));
    Files.writeString(
        skill.resolve("SKILL.md"),
        """
        ---
        name: %s
        description: Archive security test
        ---

        # Instructions

        Execute the archive security test.
        """
            .formatted(name));
    return skill;
  }

  private void assertNoArchiveEvents() throws IOException {
    Path agentArchive = oryxosRoot.resolve("archive/.skills").resolve(AGENT_NAME);
    if (!Files.isDirectory(agentArchive)) {
      return;
    }
    assertEquals(0, childCount(agentArchive));
  }

  private static long childCount(Path directory) throws IOException {
    try (var children = Files.list(directory)) {
      return children.count();
    }
  }

  private void assertSingleFailedAudit(String reasonCode) {
    List<ILoggingEvent> events =
        logs.list.stream()
            .filter(event -> "skill.management".equals(keyValue(event, "event")))
            .toList();
    assertEquals(1, events.size());
    ILoggingEvent event = events.get(0);
    assertEquals(AGENT_NAME, keyValue(event, "agent"));
    assertEquals("weather", keyValue(event, "skill"));
    assertEquals("delete", keyValue(event, "action"));
    assertEquals("failed", keyValue(event, "result"));
    assertEquals(reasonCode, keyValue(event, "reasonCode"));
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

  private static final class TestPublishOperations
      implements SkillManagementService.PublishOperations {

    private boolean sameFileStore = true;
    private IOException moveFailure;
    private int moves;

    @Override
    public boolean sameFileStore(Path source, Path targetParent) {
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

  private static final class TestArchiveOperations
      implements SkillManagementService.ArchiveOperations {

    private boolean failMetadataAfterPartialWrite;

    @Override
    public void createDirectory(Path directory) throws IOException {
      Files.createDirectory(directory);
    }

    @Override
    public void writeMetadata(Path metadataFile, byte[] yaml) throws IOException {
      if (failMetadataAfterPartialWrite) {
        Files.writeString(
            metadataFile,
            "partial: /private/detail",
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE);
        throw new IOException("/private/metadata failure");
      }
      Files.write(metadataFile, yaml, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
  }
}
