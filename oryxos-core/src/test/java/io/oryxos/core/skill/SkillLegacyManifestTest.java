package io.oryxos.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class SkillLegacyManifestTest {

  @Test
  void legacyRequiresWarnsOnceWithoutGrantingTopLevelRequirementsOrLeakingValues(
      CapturedOutput output) {
    ParsedSkill parsed =
        new SkillManifestParser()
            .parse(
                "---\nname: legacy\ndescription: safe\nmetadata:\n  openclaw:\n    requires:\n      - top-secret-value\n---\nbody",
                true);

    assertTrue(parsed.manifest().requires().skills().isEmpty());
    assertEquals(1, occurrences(output.getOut(), "LEGACY_OPENCLAW_REQUIRES"));
    assertTrue(!output.getOut().contains("top-secret-value"));
  }

  private static int occurrences(String value, String needle) {
    return value.split(needle, -1).length - 1;
  }
}
