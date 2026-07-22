package io.oryxos.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.oryxos.core.context.ContextLoader;
import io.oryxos.core.profile.Profile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class AgentSkillCatalogTest {

  private static final String AGENT_NAME = "ops-agent";

  @TempDir Path tempDir;

  private Path agentsDir;
  private Path agentDir;
  private Path skillsDir;
  private SkillLimits limits;
  private SkillMetadataReader reader;
  private SkillContentValidator validator;

  @BeforeEach
  void setUp() throws IOException {
    agentsDir = Files.createDirectories(tempDir.resolve("agents"));
    agentDir = Files.createDirectory(agentsDir.resolve(AGENT_NAME));
    skillsDir = Files.createDirectory(agentDir.resolve("skills"));
    limits = SkillLimits.defaults();
    reader = mock(SkillMetadataReader.class);
    validator = mock(SkillContentValidator.class);
  }

  @Test
  void scansOnlyManagedDirectChildrenAndIgnoresRootSymlinksWithWarning() throws Exception {
    Path weather = managed("weather", "Weather guidance", false);
    Path missing = Files.createDirectory(skillsDir.resolve("missing"));
    Files.writeString(missing.resolve(".oryxos-disabled"), "");
    when(validator.validate(missing, limits))
        .thenReturn(new SkillContentValidator.ContentStats(List.of(), 0, 0));
    Files.writeString(skillsDir.resolve("legacy-note.md"), "legacy");
    Files.createDirectory(skillsDir.resolve("references"));
    Path outside = Files.createDirectory(tempDir.resolve("outside-skill"));
    Files.writeString(outside.resolve("SKILL.md"), "outside");
    Path rootLink = Files.createSymbolicLink(skillsDir.resolve("linked"), outside);
    List<ILoggingEvent> events = captureLogs(() -> catalog(limits).list(AGENT_NAME));

    List<SkillDescriptor> descriptors = catalog(limits).list(AGENT_NAME);

    assertEquals(List.of("missing", "weather"), directoryNames(descriptors));
    assertEquals(
        SkillValidationCode.MISSING_ENTRYPOINT,
        byName(descriptors, "missing").validationError().code());
    assertEquals(SkillStatus.ENABLED, byName(descriptors, "weather").status());
    assertTrue(events.stream().anyMatch(event -> event.getMessage().contains("ROOT_SYMLINK")));
    verify(reader, never()).read(eq(agentDir), eq(rootLink), eq(limits));
    verify(reader, times(2)).read(eq(agentDir), eq(weather), eq(limits));
  }

  @Test
  void ignoresManagedLookingDirectoriesWhoseNamesContainControlCharacters() throws Exception {
    Path unsafe = Files.createDirectory(skillsDir.resolve("bad\nname"));
    Files.writeString(unsafe.resolve("SKILL.md"), "placeholder");

    List<SkillDescriptor> descriptors = catalog(limits).list(AGENT_NAME);

    assertTrue(descriptors.isEmpty());
    verifyNoInteractions(reader, validator);
  }

  @Test
  void nonCanonicalEntrypointIsAnIsolatedInvalidCandidate() throws Exception {
    Path invalid = Files.createDirectory(skillsDir.resolve("wrong-entry-case"));
    Files.writeString(invalid.resolve("skill.md"), "placeholder");
    when(validator.validate(invalid, limits))
        .thenThrow(
            new SkillValidationException(
                SkillValidationCode.MISSING_ENTRYPOINT,
                "SKILL.md must use its canonical filename"));

    List<SkillDescriptor> descriptors = catalog(limits).list(AGENT_NAME);

    assertEquals(List.of("wrong-entry-case"), directoryNames(descriptors));
    assertEquals(SkillStatus.INVALID, descriptors.get(0).status());
    assertEquals(
        SkillValidationCode.MISSING_ENTRYPOINT, descriptors.get(0).validationError().code());
    verify(reader, never()).read(agentDir, invalid, limits);
  }

  @Test
  void nonCanonicalSkillsRootIsIgnoredOnEveryFilesystem() throws Exception {
    Files.delete(skillsDir);
    Path nonCanonical = Files.createDirectory(agentDir.resolve("Skills"));
    Files.createDirectories(nonCanonical.resolve("weather"));
    Files.writeString(nonCanonical.resolve("weather/SKILL.md"), "placeholder");

    List<SkillDescriptor> descriptors = catalog(limits).list(AGENT_NAME);

    assertTrue(descriptors.isEmpty());
    verifyNoInteractions(reader, validator);
  }

  @Test
  void rejectsASymlinkedAgentsRootWithoutScanningItsTarget() throws Exception {
    Path outsideAgents = Files.createDirectories(tempDir.resolve("outside-agents"));
    Path outsideSkill =
        Files.createDirectories(
            outsideAgents.resolve(AGENT_NAME).resolve("skills").resolve("weather"));
    Files.writeString(outsideSkill.resolve("SKILL.md"), "outside");
    Path linkedAgents = Files.createSymbolicLink(tempDir.resolve("linked-agents"), outsideAgents);

    AgentSkillCatalog linkedCatalog =
        new AgentSkillCatalog(linkedAgents, reader, validator, limits);

    assertThrows(IllegalStateException.class, () -> linkedCatalog.list(AGENT_NAME));
    verifyNoInteractions(reader, validator);
  }

  @Test
  void rejectsLinkedAgentAndSkillsRootsWithoutScanningTheirTargets() throws Exception {
    Path outsideAgent = Files.createDirectories(tempDir.resolve("outside-agent"));
    Path outsideAgentSkill =
        Files.createDirectories(outsideAgent.resolve("skills").resolve("weather"));
    Files.writeString(outsideAgentSkill.resolve("SKILL.md"), "outside");
    Files.createSymbolicLink(agentsDir.resolve("linked-agent"), outsideAgent);

    Path realAgent = Files.createDirectory(agentsDir.resolve("linked-skills-agent"));
    Path outsideSkills = Files.createDirectories(tempDir.resolve("outside-skills"));
    Path outsideSkillsSkill = Files.createDirectory(outsideSkills.resolve("weather"));
    Files.writeString(outsideSkillsSkill.resolve("SKILL.md"), "outside");
    Files.createSymbolicLink(realAgent.resolve("skills"), outsideSkills);

    AgentSkillCatalog catalog = catalog(limits);

    assertThrows(IllegalStateException.class, () -> catalog.list("linked-agent"));
    assertThrows(IllegalStateException.class, () -> catalog.list("linked-skills-agent"));
    verifyNoInteractions(reader, validator);
  }

  @Test
  void oneInvalidSkillDoesNotBlockOtherSkillsOrTheAgent() throws Exception {
    managed("weather", "Weather guidance", false);
    Path broken = Files.createDirectory(skillsDir.resolve("broken"));
    Files.writeString(broken.resolve("SKILL.md"), "broken");
    SkillValidationError invalidYaml =
        new SkillValidationError(SkillValidationCode.INVALID_YAML, "SKILL.md YAML is invalid");
    when(validator.validate(broken, limits))
        .thenReturn(new SkillContentValidator.ContentStats(List.of("SKILL.md"), 1, 6));
    when(reader.read(agentDir, broken, limits))
        .thenThrow(new SkillValidationException(invalidYaml));

    List<SkillDescriptor> descriptors = catalog(limits).list(AGENT_NAME);
    SkillSnapshot snapshot = catalog(limits).snapshot(AGENT_NAME);

    assertEquals(List.of("broken", "weather"), directoryNames(descriptors));
    assertEquals(SkillStatus.INVALID, byName(descriptors, "broken").status());
    assertEquals(
        SkillValidationCode.INVALID_YAML, byName(descriptors, "broken").validationError().code());
    assertEquals(List.of("weather"), skillNames(snapshot));
  }

  @Test
  void unexpectedCandidateReadFailureIsIsolatedAsContentUnreadable() throws Exception {
    managed("weather", "Weather guidance", false);
    Path unreadable = Files.createDirectory(skillsDir.resolve("unreadable"));
    Files.writeString(unreadable.resolve("SKILL.md"), "placeholder");
    when(validator.validate(unreadable, limits))
        .thenThrow(
            new UncheckedIOException(
                new IOException("/private/secret should never reach the descriptor")));

    List<SkillDescriptor> descriptors = catalog(limits).list(AGENT_NAME);

    assertEquals(SkillStatus.INVALID, byName(descriptors, "unreadable").status());
    assertEquals(
        SkillValidationCode.CONTENT_UNREADABLE,
        byName(descriptors, "unreadable").validationError().code());
    assertFalse(
        byName(descriptors, "unreadable").validationError().message().contains("/private/"));
    assertEquals(SkillStatus.ENABLED, byName(descriptors, "weather").status());
  }

  @Test
  void validatorLinkAndReservedMarkerFailuresRemainIsolated() throws Exception {
    managed("weather", "Weather guidance", false);
    Path linked = Files.createDirectory(skillsDir.resolve("linked"));
    Files.writeString(linked.resolve("SKILL.md"), "placeholder");
    Path outside = Files.writeString(tempDir.resolve("outside.txt"), "outside");
    Files.createSymbolicLink(linked.resolve("reference.txt"), outside);
    when(validator.validate(linked, limits))
        .thenThrow(
            new SkillValidationException(
                SkillValidationCode.LINK_NOT_ALLOWED, "Skill resources must not be links"));

    Path damaged = Files.createDirectory(skillsDir.resolve("damaged"));
    Files.writeString(damaged.resolve("SKILL.md"), "placeholder");
    Files.writeString(damaged.resolve(".oryxos-disabled"), "not-empty");
    when(validator.validate(damaged, limits))
        .thenThrow(
            new SkillValidationException(
                SkillValidationCode.RESERVED_FILE_INVALID,
                ".oryxos-disabled must be an empty regular file"));

    List<SkillDescriptor> descriptors = catalog(limits).list(AGENT_NAME);

    assertEquals(SkillStatus.INVALID, byName(descriptors, "linked").status());
    assertEquals(
        SkillValidationCode.LINK_NOT_ALLOWED,
        byName(descriptors, "linked").validationError().code());
    assertEquals(SkillStatus.INVALID, byName(descriptors, "damaged").status());
    assertEquals(
        SkillValidationCode.RESERVED_FILE_INVALID,
        byName(descriptors, "damaged").validationError().code());
    assertEquals(SkillStatus.ENABLED, byName(descriptors, "weather").status());
  }

  @Test
  void snapshotContainsOnlyEnabledBudgetIncludedSkillsInNameOrder() throws Exception {
    managed("zulu", "Last alphabetically", false);
    managed("alpha", "First alphabetically", false);
    managed("disabled", "Disabled Skill", true);
    Path broken = Files.createDirectory(skillsDir.resolve("broken"));
    Files.writeString(broken.resolve(".oryxos-disabled"), "");

    List<SkillDescriptor> descriptors = catalog(limits).list(AGENT_NAME);
    SkillSnapshot snapshot = catalog(limits).snapshot(AGENT_NAME);

    assertEquals(List.of("alpha", "zulu"), skillNames(snapshot));
    assertEquals(SkillStatus.DISABLED, byName(descriptors, "disabled").status());
    assertFalse(byName(descriptors, "disabled").catalogIncluded());
    assertEquals(SkillStatus.INVALID, byName(descriptors, "broken").status());
    assertFalse(byName(descriptors, "broken").catalogIncluded());
    assertEquals(0, snapshot.omittedCount());
  }

  @Test
  void appliesTheSixtyFourSkillLimitAsADeterministicPrefix() throws Exception {
    for (int index = 65; index >= 0; index--) {
      managed("skill-" + String.format(Locale.ROOT, "%02d", index), "Skill " + index, false);
    }
    SkillLimits countLimits = withCatalogLimits(64, 12_000);
    restubForLimits(countLimits);
    List<ILoggingEvent> events = captureLogs(() -> catalog(countLimits).snapshot(AGENT_NAME));

    List<SkillDescriptor> descriptors = catalog(countLimits).list(AGENT_NAME);
    SkillSnapshot snapshot = catalog(countLimits).snapshot(AGENT_NAME);

    assertEquals(64, snapshot.skills().size());
    assertEquals("skill-00", snapshot.skills().get(0).name());
    assertEquals("skill-63", snapshot.skills().get(63).name());
    assertEquals(2, snapshot.omittedCount());
    assertFalse(byName(descriptors, "skill-64").catalogIncluded());
    assertFalse(byName(descriptors, "skill-65").catalogIncluded());
    assertTrue(events.stream().anyMatch(event -> event.getMessage().contains("CATALOG_TRUNCATED")));
  }

  @Test
  void characterBudgetNeverCutsAnEntryAndDoesNotPackLaterEntries() throws Exception {
    Path alphaDir = managed("alpha", "Short description", false);
    managed("beta", "Another description", false);
    SkillMetadata alpha = reader.read(agentDir, alphaDir, limits);
    int alphaChars = AgentSkillCatalog.renderedCatalogChars(List.of(alpha));
    SkillLimits characterLimits = withCatalogLimits(64, alphaChars);
    restubForLimits(characterLimits);

    List<SkillDescriptor> descriptors = catalog(characterLimits).list(AGENT_NAME);
    SkillSnapshot snapshot = catalog(characterLimits).snapshot(AGENT_NAME);

    assertEquals(List.of("alpha"), skillNames(snapshot));
    assertEquals(alphaChars, snapshot.renderedChars());
    assertEquals(1, snapshot.omittedCount());
    assertTrue(byName(descriptors, "alpha").catalogIncluded());
    assertFalse(byName(descriptors, "beta").catalogIncluded());
  }

  @Test
  void renderedCharsMatchesTheActualContextLoaderL1Fragment() throws Exception {
    managed("weather", "Weather\uDB40\uDC01guidance", false);
    SkillSnapshot snapshot = catalog(limits).snapshot(AGENT_NAME);
    ContextLoader contextLoader = new ContextLoader(tempDir);

    String context = contextLoader.load(profileWithReadFile(), snapshot);

    assertEquals(snapshot.renderedChars(), context.length());
  }

  @Test
  void sourceAndStateAreDerivedFreshOnEveryScan() throws Exception {
    Path weather = managed("weather", "Weather guidance", false);
    AgentSkillCatalog catalog = catalog(limits);

    SkillDescriptor initial = catalog.get(AGENT_NAME, "weather");
    Files.writeString(
        weather.resolve(".oryxos-origin.yml"),
        """
        schemaVersion: 1
        sourceType: upload
        originalFilename: weather.zip
        importedAt: 2026-07-22T10:30:00Z
        """);
    Files.writeString(weather.resolve(".oryxos-disabled"), "");
    SkillDescriptor changed = catalog.get(AGENT_NAME, "weather");

    assertEquals(SkillSource.WORKSPACE, initial.source());
    assertEquals(SkillStatus.ENABLED, initial.status());
    assertEquals(SkillSource.UPLOAD, changed.source());
    assertEquals(SkillStatus.DISABLED, changed.status());
  }

  @Test
  void enablingIsRejectedWhenTheSimulatedCatalogCannotIncludeTheSkill() throws Exception {
    managed("alpha", "Already enabled", false);
    managed("beta", "Currently disabled", true);
    SkillLimits oneSkillOnly = withCatalogLimits(1, 12_000);
    restubForLimits(oneSkillOnly);
    AgentSkillCatalog catalog = catalog(oneSkillOnly);

    SkillValidationException error =
        assertThrows(
            SkillValidationException.class, () -> catalog.validateCanEnable(AGENT_NAME, "beta"));

    assertEquals(SkillValidationCode.CATALOG_BUDGET_EXCEEDED, error.code());
  }

  @Test
  void importingIsRejectedWhenManagedCountOrFullEnabledCatalogWouldExceedLimits() throws Exception {
    Path alphaDir = managed("alpha", "Already enabled", false);
    SkillMetadata alpha = reader.read(agentDir, alphaDir, limits);
    SkillMetadata beta = metadata(skillsDir.resolve("beta"), "beta", "Candidate for import");

    SkillLimits oneManagedSkill = withCatalogLimits(1, 12_000);
    restubForLimits(oneManagedSkill);
    SkillValidationException countError =
        assertThrows(
            SkillValidationException.class,
            () -> catalog(oneManagedSkill).validateCanImport(AGENT_NAME, beta));
    assertEquals(SkillValidationCode.TOO_MANY_SKILLS, countError.code());

    SkillLimits alphaOnly =
        withCatalogLimits(64, AgentSkillCatalog.renderedCatalogChars(List.of(alpha)));
    restubForLimits(alphaOnly);
    SkillValidationException budgetError =
        assertThrows(
            SkillValidationException.class,
            () -> catalog(alphaOnly).validateCanImport(AGENT_NAME, beta));
    assertEquals(SkillValidationCode.CATALOG_BUDGET_EXCEEDED, budgetError.code());
  }

  @Test
  void invalidCandidateRetainsABoundedFilesystemUpdatedTime() throws Exception {
    Path invalid = Files.createDirectory(skillsDir.resolve("invalid"));
    Path entrypoint = Files.writeString(invalid.resolve("SKILL.md"), "broken yaml");
    FileTime modified = FileTime.from(java.time.Instant.parse("2026-07-22T12:34:56Z"));
    Files.setLastModifiedTime(entrypoint, modified);
    Files.setLastModifiedTime(invalid, modified);
    when(validator.validate(invalid, limits))
        .thenThrow(
            new SkillValidationException(
                SkillValidationCode.INVALID_YAML, "SKILL.md YAML is invalid"));

    SkillDescriptor descriptor = catalog(limits).get(AGENT_NAME, "invalid");

    assertEquals(modified.toInstant(), descriptor.updatedAt());
    assertEquals(SkillStatus.INVALID, descriptor.status());
  }

  @Test
  void invalidCandidateUpdatedTimeIncludesABoundedNestedResourceScan() throws Exception {
    Path invalid = Files.createDirectory(skillsDir.resolve("invalid-resource"));
    Path entrypoint = Files.writeString(invalid.resolve("SKILL.md"), "placeholder");
    Path references = Files.createDirectories(invalid.resolve("references"));
    Path nested = Files.writeString(references.resolve("large.txt"), "broken");
    FileTime oldTime = FileTime.from(java.time.Instant.parse("2026-07-22T12:00:00Z"));
    FileTime nestedTime = FileTime.from(java.time.Instant.parse("2026-07-22T12:34:56Z"));
    Files.setLastModifiedTime(entrypoint, oldTime);
    Files.setLastModifiedTime(references, oldTime);
    Files.setLastModifiedTime(invalid, oldTime);
    Files.setLastModifiedTime(nested, nestedTime);
    when(validator.validate(invalid, limits))
        .thenThrow(
            new SkillValidationException(
                SkillValidationCode.FILE_TOO_LARGE, "Skill resource exceeds the file limit"));

    SkillDescriptor descriptor = catalog(limits).get(AGENT_NAME, "invalid-resource");

    assertEquals(nestedTime.toInstant(), descriptor.updatedAt());
    assertEquals(SkillStatus.INVALID, descriptor.status());
  }

  @Test
  void excessiveUnmanagedChildrenDoNotBlockManagedSkillsOrTheAgent() throws Exception {
    managed("weather", "Weather guidance", false);
    for (int index = 0; index <= 1024; index++) {
      Files.createDirectory(skillsDir.resolve("noise-" + index));
    }

    List<SkillDescriptor> descriptors = catalog(limits).list(AGENT_NAME);

    assertEquals(List.of("weather"), directoryNames(descriptors));
  }

  @Test
  void candidateScanLimitKeepsADeterministicPrefixAndWarnsWithoutBlockingTheAgent()
      throws Exception {
    managed("zulu", "Last", false);
    managed("alpha", "First", false);
    managed("beta", "Second", false);
    SkillLimits bounded = withCatalogLimits(2, 2, 12_000);
    restubForLimits(bounded);

    List<ILoggingEvent> events = captureLogs(() -> catalog(bounded).list(AGENT_NAME));
    List<SkillDescriptor> descriptors = catalog(bounded).list(AGENT_NAME);

    assertEquals(List.of("alpha", "beta"), directoryNames(descriptors));
    assertTrue(
        events.stream()
            .anyMatch(event -> event.getMessage().contains("CATALOG_CANDIDATES_TRUNCATED")));
    verify(reader, never()).read(agentDir, skillsDir.resolve("zulu"), bounded);
  }

  @Test
  void missingAgentFailsTheWholeCatalogCall() {
    AgentSkillCatalog catalog = catalog(limits);

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> catalog.list("missing-agent"));

    assertTrue(error.getMessage().contains("missing-agent"));
  }

  private Path managed(String name, String description, boolean disabled) throws Exception {
    Path skillDir = Files.createDirectory(skillsDir.resolve(name));
    Files.writeString(skillDir.resolve("SKILL.md"), "placeholder");
    if (disabled) {
      Files.writeString(skillDir.resolve(".oryxos-disabled"), "");
    }
    SkillMetadata metadata = metadata(skillDir, name, description);
    when(validator.validate(skillDir, limits))
        .thenReturn(new SkillContentValidator.ContentStats(List.of("SKILL.md"), 1, 11));
    when(reader.read(agentDir, skillDir, limits)).thenReturn(metadata);
    return skillDir;
  }

  private void restubForLimits(SkillLimits changedLimits) throws Exception {
    try (var children = Files.list(skillsDir)) {
      for (Path skillDir : children.filter(Files::isDirectory).toList()) {
        String name = skillDir.getFileName().toString();
        SkillMetadata original = reader.read(agentDir, skillDir, limits);
        when(validator.validate(skillDir, changedLimits))
            .thenReturn(new SkillContentValidator.ContentStats(List.of("SKILL.md"), 1, 11));
        when(reader.read(agentDir, skillDir, changedLimits))
            .thenReturn(metadata(skillDir, name, original.description()));
      }
    }
  }

  private SkillMetadata metadata(Path skillDir, String name, String description) {
    return new SkillMetadata(
        name,
        description,
        null,
        null,
        Map.of(),
        null,
        skillDir.resolve("SKILL.md").toAbsolutePath().normalize(),
        "skills/" + name + "/SKILL.md");
  }

  private AgentSkillCatalog catalog(SkillLimits configuredLimits) {
    return new AgentSkillCatalog(agentsDir, reader, validator, configuredLimits);
  }

  private SkillLimits withCatalogLimits(int maxSkills, int maxChars) {
    return withCatalogLimits(maxSkills, limits.maxCandidatesPerAgent(), maxChars);
  }

  private SkillLimits withCatalogLimits(int maxSkills, int maxCandidates, int maxChars) {
    return new SkillLimits(
        limits.maxArchiveBytes(),
        limits.maxExpandedBytes(),
        limits.maxFileBytes(),
        limits.maxSkillMarkdownBytes(),
        limits.maxFrontmatterBytes(),
        limits.maxYamlNestingDepth(),
        limits.maxEntries(),
        limits.maxDepth(),
        limits.maxPathChars(),
        limits.maxExpansionRatio(),
        maxSkills,
        maxCandidates,
        maxChars,
        Duration.ofHours(24));
  }

  private static Profile profileWithReadFile() {
    return new Profile(
        AGENT_NAME,
        "test",
        null,
        new Profile.ProviderRef("mock", "mock", null),
        List.of("read_file"),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Profile.Settings.defaults());
  }

  private static List<String> directoryNames(List<SkillDescriptor> descriptors) {
    return descriptors.stream().map(SkillDescriptor::directoryName).toList();
  }

  private static List<String> skillNames(SkillSnapshot snapshot) {
    return snapshot.skills().stream().map(SkillMetadata::name).toList();
  }

  private static SkillDescriptor byName(List<SkillDescriptor> descriptors, String name) {
    return descriptors.stream()
        .filter(descriptor -> descriptor.directoryName().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private static List<ILoggingEvent> captureLogs(Supplier<?> action) {
    Logger logger = (Logger) LoggerFactory.getLogger(AgentSkillCatalog.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      action.get();
      return List.copyOf(appender.list);
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }
}
