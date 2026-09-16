package io.oryxos.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 041：缺侧车 → empty；写入再读回字段一致。 */
class AssetGovernanceStoreTest {

  private static final String NAME = "ops-skill";

  @TempDir Path root;

  @Test
  void missingFileReturnsEmpty() {
    AssetGovernanceStore store = new AssetGovernanceStore(root);

    AssetGovernance loaded = store.loadSkill(NAME);

    assertThat(loaded.isPresent()).isFalse();
    assertThat(loaded.owner()).isNull();
    assertThat(loaded.visibility()).isNull();
    assertThat(loaded.health()).isNull();
  }

  @Test
  void roundtripWriteRead() {
    AssetGovernanceStore store = new AssetGovernanceStore(root);
    AssetGovernance expected =
        new AssetGovernance(
            "alice",
            "3",
            AssetGovernance.Visibility.WORKSPACE,
            "medium",
            AssetGovernance.Health.DEPRECATED);

    store.saveSkill(NAME, expected);
    AssetGovernance loaded = store.loadSkill(NAME);

    assertThat(loaded.owner()).isEqualTo("alice");
    assertThat(loaded.version()).isEqualTo("3");
    assertThat(loaded.visibility()).isEqualTo(AssetGovernance.Visibility.WORKSPACE);
    assertThat(loaded.riskLevel()).isEqualTo("medium");
    assertThat(loaded.health()).isEqualTo(AssetGovernance.Health.DEPRECATED);
    assertThat(Files.isRegularFile(root.resolve("skills").resolve(NAME).resolve("GOVERNANCE.yml")))
        .isTrue();
  }
}
