package io.oryxos.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillAssociationStoreTest {

  @TempDir Path tempDir;

  @Test
  void persistsAssociationsAndSupportsDissociation() {
    SkillAssociationStore store = new SkillAssociationStore(tempDir.resolve(".oryxos"));

    store.associate("weather", "ops");
    store.associate("weather", "reporter");

    SkillAssociationStore restarted = new SkillAssociationStore(tempDir.resolve(".oryxos"));
    assertEquals(List.of("ops", "reporter"), restarted.agentsFor("weather"));
    assertTrue(restarted.skillsFor("ops").contains("weather"));

    restarted.dissociate("weather", "ops");
    assertEquals(List.of("reporter"), store.agentsFor("weather"));
    restarted.removeAgent("reporter");
    assertTrue(store.agentsFor("weather").isEmpty());
  }
}
