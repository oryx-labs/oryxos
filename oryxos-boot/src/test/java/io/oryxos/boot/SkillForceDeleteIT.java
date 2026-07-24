package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.skill.SkillInUseException;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillForceDeleteIT {

  @Test
  void normalConflictThenForceUsesAFreshServerSideScanAndNoJournal(@TempDir Path root)
      throws Exception {
    SkillMarketTestSupport.Market market = SkillMarketTestSupport.create(root, "ops", "finance");
    market
        .management()
        .importZip(
            new ByteArrayInputStream(SkillMarketTestSupport.zip("weather", "BODY")), "weather.zip");
    market.associationManager().associate("ops", "weather");

    SkillInUseException first =
        assertThrows(SkillInUseException.class, () -> market.management().delete("weather", false));
    assertEquals(List.of("ops"), first.linkedAgents());

    market.associationManager().associate("finance", "weather");
    assertEquals(
        List.of("finance", "ops"), market.management().delete("weather", true).affectedAgents());
    assertTrue(Files.notExists(root.resolve("skills/weather")));
    assertTrue(Files.notExists(root.resolve("agents/ops/skills/weather")));
    assertTrue(Files.notExists(root.resolve("agents/finance/skills/weather")));
    assertTrue(Files.notExists(root.resolve(".operations")));
  }
}
