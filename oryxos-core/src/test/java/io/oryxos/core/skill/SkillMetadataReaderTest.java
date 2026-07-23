package io.oryxos.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class SkillMetadataReaderTest {

  @TempDir Path workspace;

  private Path agentDir;
  private SkillMetadataReader reader;
  private ListAppender<ILoggingEvent> logs;

  @BeforeEach
  void setUp() throws IOException {
    agentDir = Files.createDirectories(workspace.resolve("agents/ops"));
    reader = new SkillMetadataReader();
    logs = new ListAppender<>();
    logs.start();
    ((Logger) LoggerFactory.getLogger(SkillMetadataReader.class)).addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(SkillMetadataReader.class)).detachAppender(logs);
  }

  @Test
  void readsTheStandardManifestWithoutRetainingPromptContent() throws Exception {
    Path skillDir =
        writeSkill(
            "weather",
            """
            name: weather
            description: 查询天气并给出出行建议
            license: Apache-2.0
            compatibility: Requires read_file
            metadata:
              author: example-team
              version: "1.0"
            allowed-tools: read_file shell
            unknown-extension: ignored
            version: ignored-top-level
            activation: ignored
            requires: ignored
            """,
            "# Weather\n\n执行天气流程。\n");

    SkillMetadata metadata = reader.read(agentDir, skillDir, SkillLimits.defaults());

    assertEquals("weather", metadata.name());
    assertEquals("查询天气并给出出行建议", metadata.description());
    assertEquals("Apache-2.0", metadata.license());
    assertEquals("Requires read_file", metadata.compatibility());
    assertEquals(Map.of("author", "example-team", "version", "1.0"), metadata.metadata());
    assertEquals("read_file shell", metadata.allowedTools());
    assertEquals(skillDir.resolve("SKILL.md").toAbsolutePath(), metadata.entryPath());
    assertEquals("skills/weather/SKILL.md", metadata.relativeEntry());
    assertFalse(metadata.toString().contains("执行天气流程"));
  }

  @Test
  void optionalFieldsMayBeAbsent() throws Exception {
    Path skillDir = writeSkill("weather", "name: weather\ndescription: Weather guidance", "body");

    SkillMetadata metadata = reader.read(agentDir, skillDir, SkillLimits.defaults());

    assertNull(metadata.license());
    assertNull(metadata.compatibility());
    assertEquals(Map.of(), metadata.metadata());
    assertNull(metadata.allowedTools());
  }

  @Test
  void enforcesOfficialNameGrammarAndExactParentDirectoryMatch() throws Exception {
    Path invalidName = writeSkill("Weather", "name: Weather\ndescription: test", "body");
    Path mismatch = writeSkill("weather-two", "name: weather\ndescription: test", "body");

    assertCode(SkillValidationCode.INVALID_NAME, invalidName, SkillLimits.defaults());
    assertCode(SkillValidationCode.NAME_DIRECTORY_MISMATCH, mismatch, SkillLimits.defaults());
  }

  @Test
  void validatesDescriptionMetadataAndPrompt() throws Exception {
    Path missingDescription = writeSkill("missing", "name: missing", "body");
    Path longDescription =
        writeSkill(
            "long-description", "name: long-description\ndescription: " + "x".repeat(1025), "body");
    Path nonStringMetadata =
        writeSkill(
            "bad-metadata",
            "name: bad-metadata\ndescription: test\nmetadata:\n  attempts: 3",
            "body");
    Path emptyPrompt =
        writeSkill("empty-prompt", "name: empty-prompt\ndescription: test", "\n\t  \n");

    assertCode(SkillValidationCode.MISSING_DESCRIPTION, missingDescription, SkillLimits.defaults());
    assertCode(SkillValidationCode.DESCRIPTION_TOO_LONG, longDescription, SkillLimits.defaults());
    assertCode(SkillValidationCode.INVALID_METADATA, nonStringMetadata, SkillLimits.defaults());
    assertCode(SkillValidationCode.EMPTY_PROMPT, emptyPrompt, SkillLimits.defaults());
  }

  @Test
  void countsDescriptionAndCompatibilityByUnicodeCodePoint() throws Exception {
    Path boundary =
        writeSkill(
            "unicode-boundary",
            "name: unicode-boundary\ndescription: "
                + "😀".repeat(1024)
                + "\ncompatibility: "
                + "😀".repeat(500),
            "body");
    Path descriptionOverflow =
        writeSkill(
            "description-overflow",
            "name: description-overflow\ndescription: " + "😀".repeat(1025),
            "body");
    Path compatibilityOverflow =
        writeSkill(
            "compatibility-overflow",
            "name: compatibility-overflow\ndescription: test\ncompatibility: " + "😀".repeat(501),
            "body");

    SkillMetadata metadata = reader.read(agentDir, boundary, SkillLimits.defaults());

    assertEquals(1024, metadata.description().codePointCount(0, metadata.description().length()));
    assertEquals(
        500, metadata.compatibility().codePointCount(0, metadata.compatibility().length()));
    assertCode(
        SkillValidationCode.DESCRIPTION_TOO_LONG, descriptionOverflow, SkillLimits.defaults());
    assertCode(SkillValidationCode.INVALID_YAML, compatibilityOverflow, SkillLimits.defaults());
  }

  @Test
  void rejectsUnsafeYamlFeaturesAndMalformedShapes() throws Exception {
    Path customTag =
        writeSkill("custom-tag", "name: custom-tag\ndescription: !untrusted payload", "body");
    Path duplicate =
        writeSkill(
            "duplicate", "name: duplicate\nname: replaced\ndescription: duplicate keys", "body");
    Path alias =
        writeSkill(
            "alias",
            "name: alias\ndescription: test\nmetadata:\n  author: &author team\n  copy: *author",
            "body");
    Path sequenceRoot = writeSkill("sequence", "- name\n- description", "body");

    assertCode(SkillValidationCode.UNSAFE_YAML, customTag, SkillLimits.defaults());
    assertCode(SkillValidationCode.UNSAFE_YAML, duplicate, SkillLimits.defaults());
    assertCode(SkillValidationCode.UNSAFE_YAML, alias, SkillLimits.defaults());
    assertCode(SkillValidationCode.INVALID_YAML, sequenceRoot, SkillLimits.defaults());
  }

  @Test
  void enforcesYamlNestingAndCodePointBudgets() throws Exception {
    Path nested =
        writeSkill(
            "nested",
            "name: nested\ndescription: test\nunknown:\n  one:\n    two:\n      three: value",
            "body");
    assertCode(SkillValidationCode.YAML_NESTING_TOO_DEEP, nested, limits(2, 1024));
    SkillValidationException codePointError =
        assertThrows(
            SkillValidationException.class,
            () ->
                reader.parseYaml(
                    "name: many-points\ndescription: " + "a".repeat(160), 8, 128, "many-points"));
    assertEquals(SkillValidationCode.YAML_CODE_POINTS_EXCEEDED, codePointError.code());
  }

  @Test
  void ignoresLegacyOpenClawRequiresWithOneStableWarning() throws Exception {
    Path skillDir =
        writeSkill(
            "legacy",
            """
            name: legacy
            description: Legacy metadata
            metadata:
              author: team
              openclaw:
                requires:
                  - shell
                  - secret-value-must-not-be-logged
            """,
            "body");

    SkillMetadata metadata = reader.read(agentDir, skillDir, SkillLimits.defaults());

    assertEquals(Map.of("author", "team"), metadata.metadata());
    long warningCount =
        logs.list.stream()
            .filter(
                event -> event.getFormattedMessage().contains("LEGACY_OPENCLAW_REQUIRES_IGNORED"))
            .count();
    assertEquals(1, warningCount);
    assertTrue(
        logs.list.stream()
            .noneMatch(
                event -> event.getFormattedMessage().contains("secret-value-must-not-be-logged")));
  }

  private Path writeSkill(String directoryName, String frontmatter, String body)
      throws IOException {
    Path skillDir = Files.createDirectories(agentDir.resolve("skills").resolve(directoryName));
    Files.writeString(
        skillDir.resolve("SKILL.md"), "---\n" + frontmatter.strip() + "\n---\n" + body);
    return skillDir;
  }

  private void assertCode(SkillValidationCode code, Path skillDir, SkillLimits limits) {
    SkillValidationException error =
        assertThrows(SkillValidationException.class, () -> reader.read(agentDir, skillDir, limits));
    assertEquals(code, error.code());
    assertFalse(error.getMessage().contains(workspace.toString()));
  }

  private static SkillLimits limits(int yamlDepth, long frontmatterBytes) {
    SkillLimits defaults = SkillLimits.defaults();
    return new SkillLimits(
        defaults.maxArchiveBytes(),
        defaults.maxExpandedBytes(),
        defaults.maxFileBytes(),
        defaults.maxSkillMarkdownBytes(),
        frontmatterBytes,
        yamlDepth,
        defaults.maxEntries(),
        defaults.maxDepth(),
        defaults.maxPathChars(),
        defaults.maxExpansionRatio(),
        defaults.maxSkillsPerAgent(),
        defaults.maxCandidatesPerAgent(),
        defaults.maxCatalogChars(),
        Duration.ofHours(24));
  }
}
