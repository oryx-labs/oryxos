package io.oryxos.core.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.core.policy.AssetGovernance;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 017 T019：渠道管理——断旧建新顺序、校验失败不落盘、启动恢复不带病上线。 */
class ChannelAdminServiceTest {

  @TempDir Path tempDir;

  private ChannelConfigLoader loader;
  private InboundChannelRegistry registry;
  private ProfileRegistry profileRegistry;
  private ChannelAdminService admin;
  private final StringBuilder lifecycle = new StringBuilder();

  /** 记录 start/stop 顺序的桩适配器。 */
  private final class TrackingAdapter extends StubChannelAdapter {
    TrackingAdapter(String name, String agent) {
      super(name, agent);
    }

    @Override
    public void start() {
      lifecycle.append("start:").append(name()).append(';');
      super.start();
    }

    @Override
    public void stop() {
      lifecycle.append("stop:").append(name()).append(';');
      super.stop();
    }
  }

  @BeforeEach
  void setUp() {
    loader = new ChannelConfigLoader(tempDir.resolve("channels.yaml"));
    registry = new InboundChannelRegistry();
    profileRegistry = mock(ProfileRegistry.class);
    when(profileRegistry.get("ops-agent")).thenReturn(Optional.of(mock(Profile.class)));
    Map<String, Function<ChannelConfig, InboundChannelAdapter>> factories =
        Map.of("stub", c -> new TrackingAdapter(c.name(), c.agent()));
    admin = new ChannelAdminService(loader, registry, profileRegistry, factories);
  }

  private static ChannelConfig config(String name, String agent, boolean enabled) {
    return new ChannelConfig(name, "stub", "app-id", "app-secret", agent, enabled);
  }

  @Test
  @DisplayName("add：落盘 + 立即上线，status 可见 CONNECTED")
  void addPersistsAndStarts() {
    admin.add(config("chan-a", "ops-agent", true));
    assertEquals(1, loader.loadRaw().size());
    assertEquals(ChannelStatus.State.CONNECTED, admin.status().get(0).state());
    assertTrue(lifecycle.toString().contains("start:chan-a"));
  }

  @Test
  @DisplayName("add 校验失败（Agent 不存在）：点名报错且不落盘")
  void addValidationFailureDoesNotPersist() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> admin.add(config("chan-a", "no-such-agent", true)));
    assertTrue(e.getMessage().contains("chan-a"));
    assertTrue(e.getMessage().contains("no-such-agent"));
    assertEquals(0, loader.loadRaw().size());
  }

  @Test
  @DisplayName("add 名称冲突：拒绝且不重复落盘")
  void addNameConflict() {
    admin.add(config("chan-a", "ops-agent", true));
    assertThrows(
        IllegalArgumentException.class, () -> admin.add(config("chan-a", "ops-agent", true)));
    assertEquals(1, loader.loadRaw().size());
  }

  @Test
  @DisplayName("update：先断旧连接再建新（禁止新旧并存）")
  void updateStopsOldBeforeStartingNew() {
    admin.add(config("chan-a", "ops-agent", true));
    lifecycle.setLength(0);
    admin.update("chan-a", config("chan-a", "ops-agent", true));
    assertEquals("stop:chan-a;start:chan-a;", lifecycle.toString());
  }

  @Test
  @DisplayName("remove：断开并从配置移除；不存在的渠道 404 语义报错")
  void removeStopsAndDeletes() {
    admin.add(config("chan-a", "ops-agent", true));
    admin.remove("chan-a");
    assertEquals(0, loader.loadRaw().size());
    assertEquals(0, admin.status().size());
    assertThrows(IllegalArgumentException.class, () -> admin.remove("chan-a"));
  }

  @Test
  @DisplayName("startAll：单条失败登记 ERROR 点名原因，不阻断其余渠道（SC-008 不带病上线）")
  void startAllIsolatesFailures() {
    loader.save(
        List.of(
            config("bad-agent", "no-such-agent", true),
            config("good", "ops-agent", true),
            config("off", "ops-agent", false)));
    admin.startAll();
    List<ChannelStatus> statuses = admin.status();
    assertEquals(3, statuses.size());
    ChannelStatus bad =
        statuses.stream().filter(s -> s.name().equals("bad-agent")).findFirst().get();
    assertEquals(ChannelStatus.State.ERROR, bad.state());
    assertTrue(bad.error().contains("no-such-agent"));
    ChannelStatus good = statuses.stream().filter(s -> s.name().equals("good")).findFirst().get();
    assertEquals(ChannelStatus.State.CONNECTED, good.state());
    ChannelStatus off = statuses.stream().filter(s -> s.name().equals("off")).findFirst().get();
    assertEquals(ChannelStatus.State.DISABLED, off.state());
  }

  @Test
  @DisplayName("startAll：凭证未解析（含 ${}）登记 ERROR 并点名字段（SC-008 缺凭证类）")
  void startAllReportsUnresolvedCredentials() {
    loader.save(
        List.of(
            new ChannelConfig(
                "no-cred",
                "stub",
                "${ORYXOS_TEST_MISSING_ID}",
                "${ORYXOS_TEST_MISSING_SECRET}",
                "ops-agent",
                true)));
    admin.startAll();
    ChannelStatus status = admin.status().get(0);
    assertEquals(ChannelStatus.State.ERROR, status.state());
    assertTrue(status.error().contains("no-cred"));
    assertTrue(status.error().contains("app_id"));
  }

  @Test
  @DisplayName("type 不支持：点名可用类型")
  void unsupportedType() {
    ChannelConfig wrongType = new ChannelConfig("chan-x", "wecom", "a", "b", "ops-agent", true);
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> admin.add(wrongType));
    assertTrue(e.getMessage().contains("wecom"));
    assertTrue(e.getMessage().contains("stub"));
  }

  @Test
  @DisplayName("039 US2：stopAll 停机先释放属主租约（unmanage）再断连——新属主秒级接管不等 TTL")
  void stopAllReleasesChannelLeasesBeforeStopping() {
    ChannelLeaseCoordinator coordinator = mock(ChannelLeaseCoordinator.class);
    admin.setChannelLeaseCoordinator(coordinator);
    admin.add(config("chan-a", "ops-agent", true));
    lifecycle.setLength(0);

    admin.stopAll();

    org.mockito.Mockito.verify(coordinator).unmanage("chan-a");
    assertTrue(lifecycle.toString().contains("stop:chan-a"));
  }

  @Test
  @DisplayName("039 US2：单机档（无 coordinator）stopAll 零变化")
  void stopAllWithoutCoordinatorIsUnchanged() {
    admin.add(config("chan-a", "ops-agent", true));
    lifecycle.setLength(0);
    admin.stopAll(); // 不抛 NPE
    assertTrue(lifecycle.toString().contains("stop:chan-a"));
  }

  @Test
  @DisplayName("update 未携带治理块时保留 channels.yaml 已有块")
  void updateKeepsGovernanceWhenOmitted() {
    AssetGovernance block =
        new AssetGovernance(
            "alice", "1", AssetGovernance.Visibility.PRIVATE, null, AssetGovernance.Health.ACTIVE);
    admin.add(config("chan-a", "ops-agent", true).withGovernance(block));

    admin.update("chan-a", config("chan-a", "ops-agent", false));

    AssetGovernance loaded = loader.loadRaw().get(0).governance();
    assertEquals("alice", loaded.owner());
    assertEquals(AssetGovernance.Visibility.PRIVATE, loaded.visibility());
    assertEquals(AssetGovernance.Health.ACTIVE, loaded.health());
  }
}
