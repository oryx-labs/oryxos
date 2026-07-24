package io.oryxos.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillDeleteConflictTest {

  @Test
  void normalDeleteIsReadOnlyAndForceRescansUnlinksThenArchives(@TempDir Path root)
      throws Exception {
    SkillPackageTestSupport.Market market = SkillPackageTestSupport.market(root, "zeta", "alpha");
    market
        .management()
        .importZip(
            new ByteArrayInputStream(SkillPackageTestSupport.validSkillZip("weather")),
            "weather.zip");
    market.associations().associate("zeta", "weather");
    market.associations().associate("alpha", "weather");

    SkillInUseException conflict =
        assertThrows(SkillInUseException.class, () -> market.management().delete("weather", false));
    assertEquals(List.of("alpha", "zeta"), conflict.linkedAgents());
    assertTrue(Files.isDirectory(root.resolve("skills/weather")));
    assertTrue(Files.isSymbolicLink(root.resolve("agents/alpha/skills/weather")));

    DeleteResult deleted = market.management().delete("weather", true);
    assertEquals(List.of("alpha", "zeta"), deleted.affectedAgents());
    assertTrue(deleted.archived());
    assertEquals(List.of(), market.associations().findLinkedAgents("weather"));
    assertTrue(Files.notExists(root.resolve("skills/weather")));
    try (var archives = Files.list(root.resolve("archive/.skills"))) {
      Path archive = archives.findFirst().orElseThrow();
      assertTrue(Files.isRegularFile(archive.resolve("archive.yml")));
      assertTrue(Files.isRegularFile(archive.resolve("package/SKILL.md")));
      String metadata = Files.readString(archive.resolve("archive.yml"));
      assertTrue(metadata.contains("forced: true"));
      assertTrue(metadata.contains("- alpha"));
      assertTrue(metadata.contains("- zeta"));
    }
  }
}
