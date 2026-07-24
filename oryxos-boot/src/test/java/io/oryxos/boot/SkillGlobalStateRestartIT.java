package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A new runtime instance derives global state only from marker + links. */
@Tag("integration")
class SkillGlobalStateRestartIT {

  @Test
  void disabledMarkerAndLinksSurviveRestartWithoutRecoveryWork(@TempDir Path root)
      throws Exception {
    SkillMarketTestSupport.Market first = SkillMarketTestSupport.create(root, "ops", "finance");
    first
        .management()
        .importZip(
            new ByteArrayInputStream(SkillMarketTestSupport.zip("weather", "BODY")), "weather.zip");
    first.associationManager().associate("ops", "weather");
    first.associationManager().associate("finance", "weather");
    first.management().setEnabled("weather", false);

    SkillMarketTestSupport.Market restarted = SkillMarketTestSupport.create(root, "ops", "finance");
    assertEquals("disabled", restarted.catalog().get("weather").status().name().toLowerCase());
    assertEquals(List.of(), restarted.agentCatalog().snapshot("ops").skills());
    assertTrue(Files.isSymbolicLink(root.resolve("agents/ops/skills/weather")));
    assertTrue(Files.notExists(root.resolve(".operations")));

    restarted.management().setEnabled("weather", true);
    assertEquals(
        List.of("weather"),
        restarted.agentCatalog().snapshot("finance").skills().stream()
            .map(skill -> skill.name())
            .toList());
  }
}
