package io.oryxos.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import io.oryxos.core.auth.Principal;
import io.oryxos.core.auth.Role;
import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.ChannelConfigLoader;
import io.oryxos.core.policy.AuthorizationService.Decision;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 041 资产门禁装饰器：flag 关必须原样透传；开时 OFFLINE / PRIVATE 叠加，缺元数据不加拒绝。 */
class AssetAwareAuthorizationServiceImplTest {

  private static final String AGENT = "ops-agent";

  private static final String OWNER = "alice";

  private static final String OTHER = "bob";

  private static final String KEY_NAME = "ci-key";

  @TempDir Path root;

  @Test
  void flagOffPassesThroughEvenWhenOffline() throws Exception {
    writeAgent(offlinePrivate(OWNER));
    AuthorizationService stub = allowAllButCounting();
    AssetAwareAuthorizationServiceImpl service =
        new AssetAwareAuthorizationServiceImpl(stub, new AssetGovernanceStore(root), false);

    Decision decision = service.decide(user(OTHER), Action.RUN_AGENT, ResourceRef.agent(AGENT));

    assertThat(decision.allowed()).isTrue();
    assertThat(((CountingAuth) stub).calls).isEqualTo(1);
  }

  @Test
  void offlineDeniesWhenFlagOn() throws Exception {
    writeAgent(offlinePrivate(OWNER));
    AssetAwareAuthorizationServiceImpl service = enabled(allowAllButCounting());

    Decision decision = service.decide(user(OWNER), Action.RUN_AGENT, ResourceRef.agent(AGENT));

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reason()).isEqualTo(AssetAwareAuthorizationServiceImpl.REASON_OFFLINE);
  }

  @Test
  void privateOwnerAllowedOtherUserDeniedAdminAllowed() throws Exception {
    writeAgent(activePrivate(OWNER));
    AssetAwareAuthorizationServiceImpl service = enabled(allowAllButCounting());

    assertThat(
            service.decide(user(OWNER), Action.MANAGE_AGENTS, ResourceRef.agent(AGENT)).allowed())
        .isTrue();
    Decision other = service.decide(user(OTHER), Action.MANAGE_AGENTS, ResourceRef.agent(AGENT));
    assertThat(other.allowed()).isFalse();
    assertThat(other.reason()).contains("私有");
    assertThat(
            service.decide(admin(OTHER), Action.MANAGE_AGENTS, ResourceRef.agent(AGENT)).allowed())
        .isTrue();
  }

  @Test
  void missingMetaAllows() {
    AssetAwareAuthorizationServiceImpl service = enabled(allowAllButCounting());

    Decision decision = service.decide(user(OTHER), Action.RUN_AGENT, ResourceRef.agent(AGENT));

    assertThat(decision.allowed()).isTrue();
  }

  @Test
  void delegateDenyShortCircuitsWithoutAssetGate() throws Exception {
    writeAgent(activePrivate(OWNER));
    AuthorizationService deny = (principal, action, resource) -> Decision.denied("角色不足");
    AssetAwareAuthorizationServiceImpl service =
        new AssetAwareAuthorizationServiceImpl(deny, new AssetGovernanceStore(root), true);

    Decision decision = service.decide(user(OTHER), Action.MANAGE_AGENTS, ResourceRef.agent(AGENT));

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reason()).isEqualTo("角色不足");
  }

  @Test
  void apiKeyOnlyBlockedByOffline() throws Exception {
    writeAgent(activePrivate(OWNER));
    AssetAwareAuthorizationServiceImpl service = enabled(allowAllButCounting());
    Principal key = Principal.apiKey(KEY_NAME, KEY_NAME, Set.of(Role.EDITOR));

    assertThat(service.decide(key, Action.RUN_AGENT, ResourceRef.agent(AGENT)).allowed()).isTrue();

    writeAgent(offlinePrivate(OWNER));
    Decision offline = service.decide(key, Action.RUN_AGENT, ResourceRef.agent(AGENT));
    assertThat(offline.allowed()).isFalse();
    assertThat(offline.reason()).isEqualTo(AssetAwareAuthorizationServiceImpl.REASON_OFFLINE);
  }

  private AssetAwareAuthorizationServiceImpl enabled(AuthorizationService delegate) {
    return new AssetAwareAuthorizationServiceImpl(delegate, new AssetGovernanceStore(root), true);
  }

  private void writeAgent(AssetGovernance governance) throws Exception {
    Path dir = root.resolve("agents").resolve(AGENT);
    Files.createDirectories(dir);
    Files.writeString(
        dir.resolve(AssetGovernanceStore.FILE_NAME), AssetGovernanceStore.render(governance));
  }

  private static AssetGovernance activePrivate(String owner) {
    return new AssetGovernance(
        owner, "1", AssetGovernance.Visibility.PRIVATE, null, AssetGovernance.Health.ACTIVE);
  }

  private static AssetGovernance offlinePrivate(String owner) {
    return new AssetGovernance(
        owner, "1", AssetGovernance.Visibility.PRIVATE, null, AssetGovernance.Health.OFFLINE);
  }

  private static Principal user(String id) {
    return Principal.user(id, id, Set.of(Role.EDITOR));
  }

  private static Principal admin(String id) {
    return Principal.user(id, id, Set.of(Role.ADMIN));
  }

  private static AuthorizationService allowAllButCounting() {
    return new CountingAuth();
  }

  /** 计数桩：证明装饰器只打同一接口一次委托。 */
  private static final class CountingAuth implements AuthorizationService {
    private int calls;

    @Override
    public Decision decide(Principal principal, Action action, ResourceRef resource) {
      calls++;
      return Decision.ALLOWED;
    }
  }

  @Test
  void channelOfflineDeniedWhenFlagOn() {
    writeChannel(
        new AssetGovernance(
            OWNER, "1", AssetGovernance.Visibility.PRIVATE, null, AssetGovernance.Health.OFFLINE));
    AssetAwareAuthorizationServiceImpl service = enabled(allowAllButCounting());

    Decision decision =
        service.decide(user(OWNER), Action.MANAGE_CHANNELS, ResourceRef.channel(CHANNEL));

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reason()).isEqualTo(AssetAwareAuthorizationServiceImpl.REASON_OFFLINE);
  }

  @Test
  void channelFlagOffDoesNotExtraDeny() {
    writeChannel(
        new AssetGovernance(
            OWNER, "1", AssetGovernance.Visibility.PRIVATE, null, AssetGovernance.Health.OFFLINE));
    AssetAwareAuthorizationServiceImpl service =
        new AssetAwareAuthorizationServiceImpl(
            allowAllButCounting(), new AssetGovernanceStore(root), false);

    Decision decision =
        service.decide(user(OTHER), Action.MANAGE_CHANNELS, ResourceRef.channel(CHANNEL));

    assertThat(decision.allowed()).isTrue();
  }

  private static final String CHANNEL = "ops-feishu";

  private void writeChannel(AssetGovernance governance) {
    new ChannelConfigLoader(root.resolve(AssetGovernanceStore.CHANNELS_FILE))
        .save(
            List.of(
                new ChannelConfig(CHANNEL, "feishu", "app", "secret", AGENT, true)
                    .withGovernance(governance)));
  }
}
