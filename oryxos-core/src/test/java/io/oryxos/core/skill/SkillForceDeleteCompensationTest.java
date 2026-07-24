package io.oryxos.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillForceDeleteCompensationTest {

  @Test
  void archiveFailureRecreatesOnlyLinksRemovedByThisOperation(@TempDir Path root) throws Exception {
    SkillPackageTestSupport.Market market = SkillPackageTestSupport.market(root, "ops", "other");
    market
        .management()
        .importZip(
            new ByteArrayInputStream(SkillPackageTestSupport.validSkillZip("weather")),
            "weather.zip");
    market.associations().associate("ops", "weather");
    market.associations().associate("other", "weather");
    Path archiveRoot = root.resolve("archive/.skills");
    Files.delete(archiveRoot);
    Files.createSymbolicLink(archiveRoot, root.resolve("skills"));

    assertThrows(IllegalStateException.class, () -> market.management().delete("weather", true));

    assertTrue(Files.isDirectory(root.resolve("skills/weather")));
    assertEquals(
        Path.of("../../../skills/weather"),
        Files.readSymbolicLink(root.resolve("agents/ops/skills/weather")));
    assertEquals(
        Path.of("../../../skills/weather"),
        Files.readSymbolicLink(root.resolve("agents/other/skills/weather")));
  }
}
