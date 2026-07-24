package io.oryxos.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillGlobalStateTest {

  @Test
  void markerChangesGlobalDiscoveryButPreservesEveryAssociationAcrossRestart(@TempDir Path root)
      throws Exception {
    SkillPackageTestSupport.Market market = SkillPackageTestSupport.market(root, "finance", "ops");
    market
        .management()
        .importZip(
            new ByteArrayInputStream(SkillPackageTestSupport.validSkillZip("weather")),
            "weather.zip");
    market.associations().associate("finance", "weather");
    market.associations().associate("ops", "weather");

    assertEquals(SkillStatus.DISABLED, market.management().setEnabled("weather", false).status());
    assertTrue(Files.isSymbolicLink(root.resolve("agents/finance/skills/weather")));
    assertTrue(Files.isSymbolicLink(root.resolve("agents/ops/skills/weather")));
    assertFalse(market.associations().list("ops").getFirst().discoverable());
    assertEquals(List.of(), market.agentCatalog().snapshot("ops").skills());

    PublicSkillCatalog afterRestart =
        new PublicSkillCatalog(
            root, new SkillMetadataReader(), new SkillContentValidator(), SkillLimits.defaults());
    assertEquals(SkillStatus.DISABLED, afterRestart.get("weather").status());

    assertEquals(SkillStatus.ENABLED, market.management().setEnabled("weather", true).status());
    assertEquals(
        List.of("weather"),
        market.agentCatalog().snapshot("ops").skills().stream().map(SkillMetadata::name).toList());
  }
}
