package io.oryxos.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SkillValueObjectsTest {

  private static final Instant NOW = Instant.parse("2026-07-22T10:30:00Z");

  @Test
  void collectionsAreDefensivelyCopiedAndImmutable() {
    Map<String, String> rawMetadata = new LinkedHashMap<>();
    rawMetadata.put("author", "oryxos");
    SkillMetadata metadata = metadata(rawMetadata);

    rawMetadata.put("injected", "later");

    assertEquals(Map.of("author", "oryxos"), metadata.metadata());
    assertThrows(
        UnsupportedOperationException.class, () -> metadata.metadata().put("another", "value"));

    List<String> rawResources = new ArrayList<>(List.of("scripts/fetch.sh", "SKILL.md"));
    SkillDescriptor descriptor =
        new SkillDescriptor(
            "ops-agent",
            "weather",
            metadata,
            SkillStatus.ENABLED,
            true,
            SkillSource.WORKSPACE,
            NOW,
            null,
            "skills/weather/SKILL.md",
            rawResources,
            2,
            512,
            true);

    rawResources.add("references/private.md");

    assertEquals(List.of("SKILL.md", "scripts/fetch.sh"), descriptor.resources());
    assertThrows(
        UnsupportedOperationException.class, () -> descriptor.resources().add("assets/icon.png"));

    SkillDescriptor unicodeDescriptor =
        new SkillDescriptor(
            "ops-agent",
            "weather",
            metadata,
            SkillStatus.ENABLED,
            true,
            SkillSource.WORKSPACE,
            NOW,
            null,
            "skills/weather/SKILL.md",
            List.of("assets/a😀.md", "assets/a\uE000.md"),
            2,
            10,
            true);
    assertEquals(List.of("assets/a\uE000.md", "assets/a😀.md"), unicodeDescriptor.resources());

    List<SkillMetadata> rawSkills = new ArrayList<>(List.of(metadata));
    SkillSnapshot snapshot = new SkillSnapshot("ops-agent", NOW, rawSkills, 128, 0);

    rawSkills.clear();

    assertEquals(List.of(metadata), snapshot.skills());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.skills().clear());

    List<String> rawContentResources = new ArrayList<>(List.of("scripts/fetch.sh", "SKILL.md"));
    SkillContentValidator.ContentStats stats =
        new SkillContentValidator.ContentStats(rawContentResources, 2, 512);

    rawContentResources.clear();

    assertEquals(List.of("SKILL.md", "scripts/fetch.sh"), stats.resources());
    assertThrows(UnsupportedOperationException.class, () -> stats.resources().clear());
  }

  @Test
  void skillNameGrammarIsLinearAndExact() {
    assertTrue(SkillMetadata.isValidName("a"));
    assertTrue(SkillMetadata.isValidName("weather-2"));
    assertTrue(SkillMetadata.isValidName("a".repeat(64)));

    for (String invalid :
        List.of(
            "-weather",
            "weather-",
            "weather--alerts",
            "Weather",
            "weather_alerts",
            "wéather",
            "a".repeat(65))) {
      assertFalse(SkillMetadata.isValidName(invalid), invalid);
    }
  }

  @Test
  void validationErrorKeepsStableCodeAndMessage() {
    SkillValidationError error =
        new SkillValidationError(
            SkillValidationCode.INVALID_YAML, "SKILL.md frontmatter is invalid YAML");

    assertEquals(SkillValidationCode.INVALID_YAML, error.code());
    assertEquals("SKILL.md frontmatter is invalid YAML", error.message());
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillValidationError(SkillValidationCode.INVALID_YAML, " \n "));
  }

  @Test
  void validationExceptionSerializesOnlyItsStableCodeAndMessage() throws Exception {
    SkillValidationException original =
        new SkillValidationException(
            SkillValidationCode.INVALID_YAML, "SKILL.md frontmatter is invalid YAML");
    byte[] serialized;
    try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ObjectOutputStream output = new ObjectOutputStream(bytes)) {
      output.writeObject(original);
      serialized = bytes.toByteArray();
    }

    SkillValidationException restored;
    try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(serialized))) {
      restored = (SkillValidationException) input.readObject();
    }

    assertEquals(original.code(), restored.code());
    assertEquals(original.getMessage(), restored.getMessage());
    assertEquals(original.error(), restored.error());
  }

  @Test
  void invalidStatusTakesPriorityOverConfiguredEnabledState() {
    SkillValidationError error =
        new SkillValidationError(SkillValidationCode.MISSING_ENTRYPOINT, "SKILL.md is missing");

    assertEquals(SkillStatus.INVALID, SkillStatus.resolve(true, error));
    assertEquals(SkillStatus.INVALID, SkillStatus.resolve(false, error));
    assertEquals(SkillStatus.ENABLED, SkillStatus.resolve(true, null));
    assertEquals(SkillStatus.DISABLED, SkillStatus.resolve(false, null));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SkillDescriptor(
                "ops-agent",
                "weather",
                null,
                SkillStatus.ENABLED,
                true,
                SkillSource.WORKSPACE,
                NOW,
                error,
                null,
                List.of(),
                0,
                0,
                false));
  }

  @Test
  void entryAndResourcePathsMustRemainRelativeAndContained() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SkillMetadata(
                "weather",
                "Weather guidance",
                null,
                null,
                Map.of(),
                null,
                Path.of("/srv/oryxos/agents/ops-agent/skills/weather/SKILL.md"),
                "/skills/weather/SKILL.md"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SkillMetadata(
                "weather",
                "Weather guidance",
                null,
                null,
                Map.of(),
                null,
                Path.of("/srv/oryxos/agents/ops-agent/skills/weather/SKILL.md"),
                "skills/weather/../other/SKILL.md"));

    SkillMetadata metadata = metadata(Map.of());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SkillDescriptor(
                "ops-agent",
                "weather",
                metadata,
                SkillStatus.ENABLED,
                true,
                SkillSource.WORKSPACE,
                NOW,
                null,
                metadata.relativeEntry(),
                List.of("../outside.txt"),
                1,
                10,
                true));
  }

  @Test
  void snapshotContainsOnlyL1MetadataAndNeverCarriesBodyOrResourceContent() {
    Set<String> components =
        Arrays.stream(SkillSnapshot.class.getRecordComponents())
            .map(component -> component.getName())
            .collect(Collectors.toSet());

    assertEquals(
        Set.of("agentName", "capturedAt", "skills", "renderedChars", "omittedCount"), components);
    assertFalse(components.contains("body"));
    assertFalse(components.contains("prompt"));
    assertFalse(components.contains("content"));

    SkillSnapshot empty = SkillSnapshot.empty("ops-agent");
    assertEquals("ops-agent", empty.agentName());
    assertEquals(List.of(), empty.skills());
    assertEquals(0, empty.renderedChars());
    assertEquals(0, empty.omittedCount());
  }

  @Test
  void originRepresentsOnlySanitizedUploadProvenance() {
    SkillOrigin origin = new SkillOrigin(1, SkillSource.UPLOAD, "weather.zip", NOW);

    assertEquals(1, origin.schemaVersion());
    assertEquals(SkillSource.UPLOAD, origin.sourceType());
    assertEquals("weather.zip", origin.originalFilename());
    assertEquals(NOW, origin.importedAt());

    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillOrigin(1, SkillSource.UPLOAD, "C:\\downloads\\weather.zip", NOW));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillOrigin(1, SkillSource.WORKSPACE, "weather.zip", NOW));
  }

  private static SkillMetadata metadata(Map<String, String> customMetadata) {
    return new SkillMetadata(
        "weather",
        "Weather guidance",
        "Apache-2.0",
        "Requires read_file",
        customMetadata,
        "read_file shell",
        Path.of("/srv/oryxos/agents/ops-agent/skills/weather/SKILL.md"),
        "skills/weather/SKILL.md");
  }
}
