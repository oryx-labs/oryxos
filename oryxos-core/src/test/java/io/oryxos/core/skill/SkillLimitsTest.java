package io.oryxos.core.skill;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SkillLimitsTest {

  @Test
  void defaultsUseTheDocumentedSafeBudgets() {
    SkillLimits limits = SkillLimits.defaults();

    assertAll(
        () -> assertEquals(10L * 1024 * 1024, limits.maxArchiveBytes()),
        () -> assertEquals(25L * 1024 * 1024, limits.maxExpandedBytes()),
        () -> assertEquals(5L * 1024 * 1024, limits.maxFileBytes()),
        () -> assertEquals(256L * 1024, limits.maxSkillMarkdownBytes()),
        () -> assertEquals(64L * 1024, limits.maxFrontmatterBytes()),
        () -> assertEquals(8, limits.maxYamlNestingDepth()),
        () -> assertEquals(128, limits.maxEntries()),
        () -> assertEquals(8, limits.maxDepth()),
        () -> assertEquals(512, limits.maxPathChars()),
        () -> assertEquals(100, limits.maxExpansionRatio()),
        () -> assertEquals(64, limits.maxSkillsPerAgent()),
        () -> assertEquals(1024, limits.maxCandidatesPerAgent()),
        () -> assertEquals(12_000, limits.maxCatalogChars()),
        () -> assertEquals(Duration.ofHours(24), limits.stagingTtl()));
  }

  @Test
  void everyDefaultLimitIsPositive() {
    SkillLimits limits = SkillLimits.defaults();

    assertAll(
        () -> assertTrue(limits.maxArchiveBytes() > 0),
        () -> assertTrue(limits.maxExpandedBytes() > 0),
        () -> assertTrue(limits.maxFileBytes() > 0),
        () -> assertTrue(limits.maxSkillMarkdownBytes() > 0),
        () -> assertTrue(limits.maxFrontmatterBytes() > 0),
        () -> assertTrue(limits.maxYamlNestingDepth() > 0),
        () -> assertTrue(limits.maxEntries() > 0),
        () -> assertTrue(limits.maxDepth() > 0),
        () -> assertTrue(limits.maxPathChars() > 0),
        () -> assertTrue(limits.maxExpansionRatio() > 0),
        () -> assertTrue(limits.maxSkillsPerAgent() > 0),
        () -> assertTrue(limits.maxCandidatesPerAgent() > 0),
        () -> assertTrue(limits.maxCatalogChars() > 0),
        () -> assertTrue(!limits.stagingTtl().isZero() && !limits.stagingTtl().isNegative()));
  }

  @Test
  void rejectsNonPositiveLimitsAndNamesTheField() {
    IllegalArgumentException bytesError =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                limits(
                    0,
                    25L * 1024 * 1024,
                    5L * 1024 * 1024,
                    256L * 1024,
                    64L * 1024,
                    8,
                    128,
                    8,
                    512,
                    100,
                    64,
                    12_000,
                    Duration.ofHours(24)));
    IllegalArgumentException countError =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                limits(
                    10L * 1024 * 1024,
                    25L * 1024 * 1024,
                    5L * 1024 * 1024,
                    256L * 1024,
                    64L * 1024,
                    8,
                    0,
                    8,
                    512,
                    100,
                    64,
                    12_000,
                    Duration.ofHours(24)));
    IllegalArgumentException durationError =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                limits(
                    10L * 1024 * 1024,
                    25L * 1024 * 1024,
                    5L * 1024 * 1024,
                    256L * 1024,
                    64L * 1024,
                    8,
                    128,
                    8,
                    512,
                    100,
                    64,
                    12_000,
                    Duration.ZERO));

    assertAll(
        () -> assertTrue(bytesError.getMessage().contains("maxArchiveBytes")),
        () -> assertTrue(countError.getMessage().contains("maxEntries")),
        () -> assertTrue(durationError.getMessage().contains("stagingTtl")));
  }

  @Test
  void enforcesTheNestedSizeRelationship() {
    IllegalArgumentException frontmatterError =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                limits(
                    10L * 1024 * 1024,
                    25L * 1024 * 1024,
                    5L * 1024 * 1024,
                    256L * 1024,
                    257L * 1024,
                    8,
                    128,
                    8,
                    512,
                    100,
                    64,
                    12_000,
                    Duration.ofHours(24)));
    IllegalArgumentException markdownError =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                limits(
                    10L * 1024 * 1024,
                    25L * 1024 * 1024,
                    5L * 1024 * 1024,
                    6L * 1024 * 1024,
                    64L * 1024,
                    8,
                    128,
                    8,
                    512,
                    100,
                    64,
                    12_000,
                    Duration.ofHours(24)));
    IllegalArgumentException fileError =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                limits(
                    10L * 1024 * 1024,
                    4L * 1024 * 1024,
                    5L * 1024 * 1024,
                    256L * 1024,
                    64L * 1024,
                    8,
                    128,
                    8,
                    512,
                    100,
                    64,
                    12_000,
                    Duration.ofHours(24)));

    assertAll(
        () -> assertTrue(frontmatterError.getMessage().contains("maxFrontmatterBytes")),
        () -> assertTrue(markdownError.getMessage().contains("maxSkillMarkdownBytes")),
        () -> assertTrue(fileError.getMessage().contains("maxFileBytes")));
  }

  @Test
  void enforcesCandidateScanLimitAndItsRelationshipToRuntimeLimit() {
    SkillLimits defaults = SkillLimits.defaults();
    IllegalArgumentException positiveError =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new SkillLimits(
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
                    1,
                    0,
                    defaults.maxCatalogChars(),
                    defaults.stagingTtl()));
    IllegalArgumentException relationshipError =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new SkillLimits(
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
                    65,
                    64,
                    defaults.maxCatalogChars(),
                    defaults.stagingTtl()));

    assertTrue(positiveError.getMessage().contains("maxCandidatesPerAgent"));
    assertTrue(relationshipError.getMessage().contains("maxSkillsPerAgent"));
    assertTrue(relationshipError.getMessage().contains("maxCandidatesPerAgent"));
  }

  private static SkillLimits limits(
      long maxArchiveBytes,
      long maxExpandedBytes,
      long maxFileBytes,
      long maxSkillMarkdownBytes,
      long maxFrontmatterBytes,
      int maxYamlNestingDepth,
      int maxEntries,
      int maxDepth,
      int maxPathChars,
      int maxExpansionRatio,
      int maxSkillsPerAgent,
      int maxCatalogChars,
      Duration stagingTtl) {
    return new SkillLimits(
        maxArchiveBytes,
        maxExpandedBytes,
        maxFileBytes,
        maxSkillMarkdownBytes,
        maxFrontmatterBytes,
        maxYamlNestingDepth,
        maxEntries,
        maxDepth,
        maxPathChars,
        maxExpansionRatio,
        maxSkillsPerAgent,
        1024,
        maxCatalogChars,
        stagingTtl);
  }
}
