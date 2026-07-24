package io.oryxos.core.skill;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Informational activation hints; they never trigger execution or grant Tool access. */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "The canonical constructor replaces every List with an immutable List.copyOf.")
public record ActivationCriteria(
    List<String> keywords,
    List<String> excludeKeywords,
    List<String> patterns,
    List<String> tags,
    Integer maxContextTokens,
    String setupMarker) {

  private static final String PARENT_SEGMENT = "..";

  public ActivationCriteria {
    keywords = immutable(keywords);
    excludeKeywords = immutable(excludeKeywords);
    patterns = immutable(patterns);
    tags = immutable(tags);
  }

  public static ActivationCriteria empty() {
    return new ActivationCriteria(List.of(), List.of(), List.of(), List.of(), null, null);
  }

  public ActivationCriteria enforceLimits() {
    String marker = setupMarker;
    if (isUnsafeMarker(marker)) {
      marker = null;
    }
    return new ActivationCriteria(
        filterAndLimit(keywords, SkillManifestLimits.MAX_KEYWORDS, true),
        filterAndLimit(excludeKeywords, SkillManifestLimits.MAX_EXCLUDE_KEYWORDS, true),
        filterAndLimit(patterns, SkillManifestLimits.MAX_PATTERNS, false),
        filterAndLimit(tags, SkillManifestLimits.MAX_TAGS, true),
        maxContextTokens,
        marker);
  }

  private static boolean isUnsafeMarker(String marker) {
    if (marker == null) {
      return false;
    }
    if (marker.contains(PARENT_SEGMENT)) {
      return true;
    }
    return marker.getBytes(StandardCharsets.UTF_8).length
        > SkillManifestLimits.MAX_SETUP_MARKER_BYTES;
  }

  private static List<String> immutable(List<String> values) {
    return values == null ? List.of() : List.copyOf(values);
  }

  private static List<String> filterAndLimit(List<String> values, int max, boolean filterShort) {
    return values.stream()
        .filter(value -> value != null)
        .filter(
            value ->
                !filterShort
                    || value.codePointCount(0, value.length())
                        >= SkillManifestLimits.MIN_KEYWORD_OR_TAG_LENGTH)
        .limit(max)
        .toList();
  }
}
