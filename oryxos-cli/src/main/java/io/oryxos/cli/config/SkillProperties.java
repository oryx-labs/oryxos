package io.oryxos.cli.config;

import io.oryxos.core.skill.SkillLimits;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Binds {@code oryxos.skills.*} and converts Spring-friendly DataSize values into the pure core
 * {@link SkillLimits} contract.
 *
 * <p>{@link #toLimits()} is the startup validation boundary. Every failure names the exact external
 * configuration key so an operator can repair the deployment without reverse-mapping Java fields.
 */
@ConfigurationProperties(prefix = "oryxos.skills")
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
    justification =
        "packageLimits and catalog are intentionally mutable nested JavaBeans used only during"
            + " Spring Boot configuration binding; toLimits() validates and copies them into an"
            + " immutable core SkillLimits value before runtime use.")
public class SkillProperties {

  private static final String PREFIX = "oryxos.skills.";

  private Duration stagingTtl = Duration.ofHours(24);
  private PackageLimits packageLimits = new PackageLimits();
  private Catalog catalog = new Catalog();

  public Duration getStagingTtl() {
    return stagingTtl;
  }

  public void setStagingTtl(Duration stagingTtl) {
    this.stagingTtl = stagingTtl;
  }

  public PackageLimits getPackageLimits() {
    return packageLimits;
  }

  public void setPackageLimits(PackageLimits packageLimits) {
    this.packageLimits = packageLimits;
  }

  public Catalog getCatalog() {
    return catalog;
  }

  public void setCatalog(Catalog catalog) {
    this.catalog = catalog;
  }

  /** Validates all configured values and converts them to the core value object. */
  public SkillLimits toLimits() {
    if (packageLimits == null) {
      throw invalid(PREFIX + "package-limits", "must be configured");
    }
    if (catalog == null) {
      throw invalid(PREFIX + "catalog", "must be configured");
    }

    long maxArchiveBytes =
        positiveBytes(PREFIX + "package-limits.max-archive-size", packageLimits.maxArchiveSize);
    long maxExpandedBytes =
        positiveBytes(PREFIX + "package-limits.max-expanded-size", packageLimits.maxExpandedSize);
    long maxFileBytes =
        positiveBytes(PREFIX + "package-limits.max-file-size", packageLimits.maxFileSize);
    long maxSkillMarkdownBytes =
        positiveBytes(
            PREFIX + "package-limits.max-skill-markdown-size", packageLimits.maxSkillMarkdownSize);
    long maxFrontmatterBytes =
        positiveBytes(
            PREFIX + "package-limits.max-frontmatter-size", packageLimits.maxFrontmatterSize);

    requireAtMost(
        PREFIX + "package-limits.max-frontmatter-size",
        maxFrontmatterBytes,
        PREFIX + "package-limits.max-skill-markdown-size",
        maxSkillMarkdownBytes);
    requireAtMost(
        PREFIX + "package-limits.max-skill-markdown-size",
        maxSkillMarkdownBytes,
        PREFIX + "package-limits.max-file-size",
        maxFileBytes);
    requireAtMost(
        PREFIX + "package-limits.max-file-size",
        maxFileBytes,
        PREFIX + "package-limits.max-expanded-size",
        maxExpandedBytes);

    int maxSkillsPerAgent =
        positive(PREFIX + "catalog.max-skills-per-agent", catalog.maxSkillsPerAgent);
    int maxCandidatesPerAgent =
        positive(PREFIX + "catalog.max-candidates-per-agent", catalog.maxCandidatesPerAgent);
    requireAtMost(
        PREFIX + "catalog.max-skills-per-agent",
        maxSkillsPerAgent,
        PREFIX + "catalog.max-candidates-per-agent",
        maxCandidatesPerAgent);

    return new SkillLimits(
        maxArchiveBytes,
        maxExpandedBytes,
        maxFileBytes,
        maxSkillMarkdownBytes,
        maxFrontmatterBytes,
        positive(
            PREFIX + "package-limits.max-yaml-nesting-depth", packageLimits.maxYamlNestingDepth),
        positive(PREFIX + "package-limits.max-entries", packageLimits.maxEntries),
        positive(PREFIX + "package-limits.max-depth", packageLimits.maxDepth),
        positive(PREFIX + "package-limits.max-path-chars", packageLimits.maxPathChars),
        positive(PREFIX + "package-limits.max-expansion-ratio", packageLimits.maxExpansionRatio),
        maxSkillsPerAgent,
        maxCandidatesPerAgent,
        positive(PREFIX + "catalog.max-l1-chars", catalog.maxL1Chars),
        positiveDuration(PREFIX + "staging-ttl", stagingTtl));
  }

  /** Alias used by runtime assembly when it only needs fail-fast validation. */
  public void validate() {
    toLimits();
  }

  private static long positiveBytes(String key, DataSize value) {
    if (value == null || value.toBytes() <= 0) {
      throw invalid(key, "must be positive");
    }
    return value.toBytes();
  }

  private static int positive(String key, int value) {
    if (value <= 0) {
      throw invalid(key, "must be positive");
    }
    return value;
  }

  private static Duration positiveDuration(String key, Duration value) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw invalid(key, "must be positive");
    }
    return value;
  }

  private static void requireAtMost(
      String smallerKey, long smaller, String largerKey, long larger) {
    if (smaller > larger) {
      throw invalid(smallerKey + " / " + largerKey, "must be ordered from smaller to larger");
    }
  }

  private static IllegalStateException invalid(String key, String detail) {
    return new IllegalStateException("Invalid configuration " + key + ": " + detail);
  }

  /** Archive and extracted package limits. */
  public static class PackageLimits {

    private DataSize maxArchiveSize = DataSize.ofMegabytes(10);
    private DataSize maxExpandedSize = DataSize.ofMegabytes(25);
    private DataSize maxFileSize = DataSize.ofMegabytes(5);
    private DataSize maxSkillMarkdownSize = DataSize.ofKilobytes(256);
    private DataSize maxFrontmatterSize = DataSize.ofKilobytes(64);
    private int maxYamlNestingDepth = 8;
    private int maxEntries = 128;
    private int maxDepth = 8;
    private int maxPathChars = 512;
    private int maxExpansionRatio = 100;

    public DataSize getMaxArchiveSize() {
      return maxArchiveSize;
    }

    public void setMaxArchiveSize(DataSize maxArchiveSize) {
      this.maxArchiveSize = maxArchiveSize;
    }

    public DataSize getMaxExpandedSize() {
      return maxExpandedSize;
    }

    public void setMaxExpandedSize(DataSize maxExpandedSize) {
      this.maxExpandedSize = maxExpandedSize;
    }

    public DataSize getMaxFileSize() {
      return maxFileSize;
    }

    public void setMaxFileSize(DataSize maxFileSize) {
      this.maxFileSize = maxFileSize;
    }

    public DataSize getMaxSkillMarkdownSize() {
      return maxSkillMarkdownSize;
    }

    public void setMaxSkillMarkdownSize(DataSize maxSkillMarkdownSize) {
      this.maxSkillMarkdownSize = maxSkillMarkdownSize;
    }

    public DataSize getMaxFrontmatterSize() {
      return maxFrontmatterSize;
    }

    public void setMaxFrontmatterSize(DataSize maxFrontmatterSize) {
      this.maxFrontmatterSize = maxFrontmatterSize;
    }

    public int getMaxYamlNestingDepth() {
      return maxYamlNestingDepth;
    }

    public void setMaxYamlNestingDepth(int maxYamlNestingDepth) {
      this.maxYamlNestingDepth = maxYamlNestingDepth;
    }

    public int getMaxEntries() {
      return maxEntries;
    }

    public void setMaxEntries(int maxEntries) {
      this.maxEntries = maxEntries;
    }

    public int getMaxDepth() {
      return maxDepth;
    }

    public void setMaxDepth(int maxDepth) {
      this.maxDepth = maxDepth;
    }

    public int getMaxPathChars() {
      return maxPathChars;
    }

    public void setMaxPathChars(int maxPathChars) {
      this.maxPathChars = maxPathChars;
    }

    public int getMaxExpansionRatio() {
      return maxExpansionRatio;
    }

    public void setMaxExpansionRatio(int maxExpansionRatio) {
      this.maxExpansionRatio = maxExpansionRatio;
    }
  }

  /** Runtime L1 catalog limits. */
  public static class Catalog {

    private int maxSkillsPerAgent = 64;
    private int maxCandidatesPerAgent = 1024;
    private int maxL1Chars = 12_000;

    public int getMaxSkillsPerAgent() {
      return maxSkillsPerAgent;
    }

    public void setMaxSkillsPerAgent(int maxSkillsPerAgent) {
      this.maxSkillsPerAgent = maxSkillsPerAgent;
    }

    public int getMaxCandidatesPerAgent() {
      return maxCandidatesPerAgent;
    }

    public void setMaxCandidatesPerAgent(int maxCandidatesPerAgent) {
      this.maxCandidatesPerAgent = maxCandidatesPerAgent;
    }

    public int getMaxL1Chars() {
      return maxL1Chars;
    }

    public void setMaxL1Chars(int maxL1Chars) {
      this.maxL1Chars = maxL1Chars;
    }
  }
}
