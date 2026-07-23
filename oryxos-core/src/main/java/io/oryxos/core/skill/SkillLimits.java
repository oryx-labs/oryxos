package io.oryxos.core.skill;

import java.time.Duration;

/**
 * Skill 目录、frontmatter、导入包和 L1 catalog 的统一安全预算。
 *
 * <p>Core 只接收已换算好的 byte/count 值，不读取 Spring Environment。构造即校验，避免任一调用方绕过装配层后创建无界限制。
 */
public record SkillLimits(
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
    int maxCandidatesPerAgent,
    int maxCatalogChars,
    Duration stagingTtl) {

  private static final long KIBIBYTE = 1024L;
  private static final long MEBIBYTE = KIBIBYTE * KIBIBYTE;

  public SkillLimits {
    requirePositive("maxArchiveBytes", maxArchiveBytes);
    requirePositive("maxExpandedBytes", maxExpandedBytes);
    requirePositive("maxFileBytes", maxFileBytes);
    requirePositive("maxSkillMarkdownBytes", maxSkillMarkdownBytes);
    requirePositive("maxFrontmatterBytes", maxFrontmatterBytes);
    requirePositive("maxYamlNestingDepth", maxYamlNestingDepth);
    requirePositive("maxEntries", maxEntries);
    requirePositive("maxDepth", maxDepth);
    requirePositive("maxPathChars", maxPathChars);
    requirePositive("maxExpansionRatio", maxExpansionRatio);
    requirePositive("maxSkillsPerAgent", maxSkillsPerAgent);
    requirePositive("maxCandidatesPerAgent", maxCandidatesPerAgent);
    requirePositive("maxCatalogChars", maxCatalogChars);
    if (stagingTtl == null || stagingTtl.isZero() || stagingTtl.isNegative()) {
      throw new IllegalArgumentException("stagingTtl must be positive");
    }

    requireAtMost(
        "maxFrontmatterBytes", maxFrontmatterBytes, "maxSkillMarkdownBytes", maxSkillMarkdownBytes);
    requireAtMost("maxSkillMarkdownBytes", maxSkillMarkdownBytes, "maxFileBytes", maxFileBytes);
    requireAtMost("maxFileBytes", maxFileBytes, "maxExpandedBytes", maxExpandedBytes);
    requireAtMost(
        "maxSkillsPerAgent", maxSkillsPerAgent, "maxCandidatesPerAgent", maxCandidatesPerAgent);
  }

  /** Returns the conservative defaults documented by the Skill package contract. */
  public static SkillLimits defaults() {
    return new SkillLimits(
        10 * MEBIBYTE,
        25 * MEBIBYTE,
        5 * MEBIBYTE,
        256 * KIBIBYTE,
        64 * KIBIBYTE,
        8,
        128,
        8,
        512,
        100,
        64,
        1024,
        12_000,
        Duration.ofHours(24));
  }

  private static void requirePositive(String field, long value) {
    if (value <= 0) {
      throw new IllegalArgumentException(field + " must be positive");
    }
  }

  private static void requireAtMost(
      String smallerField, long smaller, String largerField, long larger) {
    if (smaller > larger) {
      throw new IllegalArgumentException(smallerField + " must not exceed " + largerField);
    }
  }
}
