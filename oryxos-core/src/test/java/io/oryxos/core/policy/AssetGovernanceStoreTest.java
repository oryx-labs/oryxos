package io.oryxos.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.ChannelConfigLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

  @Test
  void loadChannelOfflinePrivateAndMissing() {
    AssetGovernanceStore store = new AssetGovernanceStore(root);
    assertThat(store.load(ResourceRef.TYPE_CHANNEL, "offline-chan").isPresent()).isFalse();

    AssetGovernance offline =
        new AssetGovernance(
            "alice",
            "2",
            AssetGovernance.Visibility.PRIVATE,
            "low",
            AssetGovernance.Health.OFFLINE);
    AssetGovernance activePrivate =
        new AssetGovernance(
            "bob", null, AssetGovernance.Visibility.PRIVATE, null, AssetGovernance.Health.ACTIVE);
    new ChannelConfigLoader(root.resolve(AssetGovernanceStore.CHANNELS_FILE))
        .save(
            List.of(
                channel("offline-chan").withGovernance(offline),
                channel("private-chan").withGovernance(activePrivate),
                channel("unset-chan")));

    AssetGovernance loadedOffline = store.load(ResourceRef.TYPE_CHANNEL, "offline-chan");
    assertThat(loadedOffline.health()).isEqualTo(AssetGovernance.Health.OFFLINE);
    assertThat(loadedOffline.visibility()).isEqualTo(AssetGovernance.Visibility.PRIVATE);
    assertThat(loadedOffline.owner()).isEqualTo("alice");

    AssetGovernance loadedPrivate = store.load(ResourceRef.TYPE_CHANNEL, "private-chan");
    assertThat(loadedPrivate.visibility()).isEqualTo(AssetGovernance.Visibility.PRIVATE);
    assertThat(loadedPrivate.health()).isEqualTo(AssetGovernance.Health.ACTIVE);
    assertThat(loadedPrivate.owner()).isEqualTo("bob");

    assertThat(store.load(ResourceRef.TYPE_CHANNEL, "unset-chan"))
        .isEqualTo(AssetGovernance.empty());
    assertThat(store.load(ResourceRef.TYPE_CHANNEL, "missing-chan").isPresent()).isFalse();
  }

  private static ChannelConfig channel(String name) {
    return new ChannelConfig(name, "feishu", "app", "secret", "ops-agent", true);
  }
}
