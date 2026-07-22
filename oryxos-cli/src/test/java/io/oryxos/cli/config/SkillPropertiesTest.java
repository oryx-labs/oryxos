package io.oryxos.cli.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.skill.SkillLimits;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class SkillPropertiesTest {

  @Test
  void emptyConfigurationUsesSafeDefaults() {
    SkillLimits limits = new SkillProperties().toLimits();

    assertAll(
        () -> assertEquals(10L * 1024 * 1024, limits.maxArchiveBytes()),
        () -> assertEquals(25L * 1024 * 1024, limits.maxExpandedBytes()),
        () -> assertEquals(5L * 1024 * 1024, limits.maxFileBytes()),
        () -> assertEquals(256L * 1024, limits.maxSkillMarkdownBytes()),
        () -> assertEquals(64L * 1024, limits.maxFrontmatterBytes()),
        () -> assertEquals(64, limits.maxSkillsPerAgent()),
        () -> assertEquals(1024, limits.maxCandidatesPerAgent()),
        () -> assertEquals(12_000, limits.maxCatalogChars()),
        () -> assertEquals(Duration.ofHours(24), limits.stagingTtl()));
  }

  @Test
  void springBinderConvertsDataSizesDurationAndNestedGroups() {
    SkillProperties properties =
        bind(
            Map.ofEntries(
                Map.entry("oryxos.skills.staging-ttl", "90m"),
                Map.entry("oryxos.skills.package-limits.max-archive-size", "6MB"),
                Map.entry("oryxos.skills.package-limits.max-expanded-size", "12MB"),
                Map.entry("oryxos.skills.package-limits.max-file-size", "3MB"),
                Map.entry("oryxos.skills.package-limits.max-skill-markdown-size", "192KB"),
                Map.entry("oryxos.skills.package-limits.max-frontmatter-size", "48KB"),
                Map.entry("oryxos.skills.package-limits.max-yaml-nesting-depth", "6"),
                Map.entry("oryxos.skills.package-limits.max-entries", "80"),
                Map.entry("oryxos.skills.package-limits.max-depth", "7"),
                Map.entry("oryxos.skills.package-limits.max-path-chars", "400"),
                Map.entry("oryxos.skills.package-limits.max-expansion-ratio", "60"),
                Map.entry("oryxos.skills.catalog.max-skills-per-agent", "40"),
                Map.entry("oryxos.skills.catalog.max-candidates-per-agent", "400"),
                Map.entry("oryxos.skills.catalog.max-l1-chars", "9000")));

    SkillLimits limits = properties.toLimits();

    assertAll(
        () -> assertEquals(6L * 1024 * 1024, limits.maxArchiveBytes()),
        () -> assertEquals(12L * 1024 * 1024, limits.maxExpandedBytes()),
        () -> assertEquals(3L * 1024 * 1024, limits.maxFileBytes()),
        () -> assertEquals(192L * 1024, limits.maxSkillMarkdownBytes()),
        () -> assertEquals(48L * 1024, limits.maxFrontmatterBytes()),
        () -> assertEquals(6, limits.maxYamlNestingDepth()),
        () -> assertEquals(80, limits.maxEntries()),
        () -> assertEquals(7, limits.maxDepth()),
        () -> assertEquals(400, limits.maxPathChars()),
        () -> assertEquals(60, limits.maxExpansionRatio()),
        () -> assertEquals(40, limits.maxSkillsPerAgent()),
        () -> assertEquals(400, limits.maxCandidatesPerAgent()),
        () -> assertEquals(9000, limits.maxCatalogChars()),
        () -> assertEquals(Duration.ofMinutes(90), limits.stagingTtl()));
  }

  @Test
  void everyNonPositiveNumericValueNamesItsConfigurationKey() {
    Map<String, String> invalidValues =
        Map.ofEntries(
            Map.entry("oryxos.skills.package-limits.max-archive-size", "0B"),
            Map.entry("oryxos.skills.package-limits.max-expanded-size", "0B"),
            Map.entry("oryxos.skills.package-limits.max-file-size", "0B"),
            Map.entry("oryxos.skills.package-limits.max-skill-markdown-size", "0B"),
            Map.entry("oryxos.skills.package-limits.max-frontmatter-size", "0B"),
            Map.entry("oryxos.skills.package-limits.max-yaml-nesting-depth", "0"),
            Map.entry("oryxos.skills.package-limits.max-entries", "0"),
            Map.entry("oryxos.skills.package-limits.max-depth", "0"),
            Map.entry("oryxos.skills.package-limits.max-path-chars", "0"),
            Map.entry("oryxos.skills.package-limits.max-expansion-ratio", "0"),
            Map.entry("oryxos.skills.catalog.max-skills-per-agent", "0"),
            Map.entry("oryxos.skills.catalog.max-candidates-per-agent", "0"),
            Map.entry("oryxos.skills.catalog.max-l1-chars", "0"));

    for (Map.Entry<String, String> invalid : invalidValues.entrySet()) {
      SkillProperties properties = bind(Map.of(invalid.getKey(), invalid.getValue()));
      IllegalStateException error =
          assertThrows(IllegalStateException.class, properties::toLimits, invalid.getKey());
      assertTrue(error.getMessage().contains(invalid.getKey()), invalid.getKey());
    }
  }

  @Test
  void invalidSizeRelationshipNamesBothConfigurationKeys() {
    SkillProperties properties =
        bind(
            Map.of(
                "oryxos.skills.package-limits.max-frontmatter-size",
                "300KB",
                "oryxos.skills.package-limits.max-skill-markdown-size",
                "256KB"));

    IllegalStateException error = assertThrows(IllegalStateException.class, properties::toLimits);

    assertAll(
        () ->
            assertTrue(
                error.getMessage().contains("oryxos.skills.package-limits.max-frontmatter-size")),
        () ->
            assertTrue(
                error
                    .getMessage()
                    .contains("oryxos.skills.package-limits.max-skill-markdown-size")));
  }

  @Test
  void nonPositiveDurationNamesTheConfigurationKey() {
    SkillProperties properties = bind(Map.of("oryxos.skills.staging-ttl", "0s"));

    IllegalStateException error = assertThrows(IllegalStateException.class, properties::toLimits);

    assertTrue(error.getMessage().contains("oryxos.skills.staging-ttl"));
  }

  @Test
  void candidateScanLimitCannotBeSmallerThanTheRuntimeSkillLimit() {
    SkillProperties properties =
        bind(
            Map.of(
                "oryxos.skills.catalog.max-skills-per-agent",
                "65",
                "oryxos.skills.catalog.max-candidates-per-agent",
                "64"));

    IllegalStateException error = assertThrows(IllegalStateException.class, properties::toLimits);

    assertTrue(error.getMessage().contains("oryxos.skills.catalog.max-skills-per-agent"));
    assertTrue(error.getMessage().contains("oryxos.skills.catalog.max-candidates-per-agent"));
  }

  private static SkillProperties bind(Map<String, String> values) {
    Binder binder = new Binder(new MapConfigurationPropertySource(values));
    return binder
        .bind("oryxos.skills", Bindable.of(SkillProperties.class))
        .orElseGet(SkillProperties::new);
  }
}
