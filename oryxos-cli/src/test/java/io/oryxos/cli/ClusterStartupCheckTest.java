package io.oryxos.cli;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.oryxos.core.cluster.ClusterProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 误配 fail-fast 三组合 + 单机档零校验（026 T008 / SC-009）。 */
class ClusterStartupCheckTest {

  private static ClusterProperties cluster(boolean enabled) {
    ClusterProperties properties = new ClusterProperties();
    properties.setEnabled(enabled);
    return properties;
  }

  @Test
  void disabled_skipsAllChecks() {
    // 单机档：即使组合全是单机组件也零校验零变化
    assertThatCode(
            () ->
                new ClusterStartupCheck(
                        cluster(false), "jdbc:sqlite:oryxos.db", "markdown", "memory")
                    .afterSingletonsInstantiated())
        .doesNotThrowAnyException();
  }

  @Test
  void enabled_rejectsSqliteDatasource() {
    assertThatThrownBy(
            () ->
                new ClusterStartupCheck(cluster(true), "jdbc:sqlite:oryxos.db", "sqlite", "sqlite")
                    .afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PostgreSQL");
  }

  @Test
  void enabled_rejectsMarkdownMemory() {
    assertThatThrownBy(
            () ->
                new ClusterStartupCheck(
                        cluster(true), "jdbc:postgresql://db/oryxos", "markdown", "sqlite")
                    .afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("memory.backend=sqlite");
  }

  @Test
  void enabled_rejectsInMemoryKnowledge() {
    assertThatThrownBy(
            () ->
                new ClusterStartupCheck(
                        cluster(true), "jdbc:postgresql://db/oryxos", "sqlite", "memory")
                    .afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("knowledge.store=sqlite");
  }

  @Test
  void enabled_passesWithSharedComponents() {
    assertThatCode(
            () ->
                new ClusterStartupCheck(
                        cluster(true), "jdbc:postgresql://db/oryxos", "sqlite", "sqlite")
                    .afterSingletonsInstantiated())
        .doesNotThrowAnyException();
  }

  private static ClusterProperties sharedCluster(Duration leaseTtl, Duration heartbeat) {
    ClusterProperties properties = cluster(true);
    properties.setLeaseTtl(leaseTtl);
    properties.setHeartbeatInterval(heartbeat);
    return properties;
  }

  private static void check(ClusterProperties properties) {
    new ClusterStartupCheck(properties, "jdbc:postgresql://db/oryxos", "sqlite", "sqlite")
        .afterSingletonsInstantiated();
  }

  @Test
  void enabled_rejectsHeartbeatNotShorterThanLease() {
    // 续租间隔 45s、租约 30s：两次续租之间租约必然过期，两副本持续互踢。
    assertThatThrownBy(() -> check(sharedCluster(Duration.ofSeconds(30), Duration.ofSeconds(45))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lease-ttl");
  }

  @Test
  void enabled_rejectsHeartbeatEqualToLease() {
    assertThatThrownBy(() -> check(sharedCluster(Duration.ofSeconds(30), Duration.ofSeconds(30))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lease-ttl");
  }

  @Test
  void enabled_acceptsHeartbeatShorterThanLease() {
    assertThatCode(() -> check(sharedCluster(Duration.ofSeconds(30), Duration.ofSeconds(10))))
        .doesNotThrowAnyException();
  }

  @Test
  void enabled_acceptsDefaultHeartbeat() {
    // 不显式配 heartbeat 时缺省就是 leaseTtl/3，永远满足不变式。
    ClusterProperties properties = cluster(true);
    properties.setLeaseTtl(Duration.ofSeconds(30));
    assertThatCode(() -> check(properties)).doesNotThrowAnyException();
  }
}
