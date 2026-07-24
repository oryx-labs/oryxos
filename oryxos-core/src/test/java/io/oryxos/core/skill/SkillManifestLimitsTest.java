package io.oryxos.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class SkillManifestLimitsTest {

  @Test
  void deterministicallyFiltersAndTruncatesActivationAndRequires() {
    ActivationCriteria activation =
        new ActivationCriteria(
            values("keyword", 25),
            values("exclude", 25),
            values("pattern", 8),
            List.of("x", "ok", "valid", "tag", "more"),
            2000,
            "../unsafe");
    GatingRequirements requires =
        new GatingRequirements(
            List.of("git"), List.of("TOKEN"), List.of("config"), values("skill", 15));

    ActivationCriteria limited = activation.enforceLimits();
    GatingRequirements limitedRequires = requires.enforceLimits();

    assertEquals(20, limited.keywords().size());
    assertEquals(20, limited.excludeKeywords().size());
    assertEquals(5, limited.patterns().size());
    assertEquals(List.of("valid", "tag", "more"), limited.tags());
    assertNull(limited.setupMarker());
    assertEquals(10, limitedRequires.skills().size());
  }

  @Test
  void clearsSetupMarkerLargerThan256Utf8Bytes() {
    ActivationCriteria activation =
        new ActivationCriteria(List.of(), List.of(), List.of(), List.of(), null, "界".repeat(86));

    assertNull(activation.enforceLimits().setupMarker());
  }

  private static List<String> values(String prefix, int count) {
    return java.util.stream.IntStream.range(0, count).mapToObj(index -> prefix + index).toList();
  }
}
