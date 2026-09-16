package io.oryxos.core.policy;

import io.oryxos.core.auth.Principal;
import io.oryxos.core.auth.Role;

/**
 * 资产治理装饰器（041 / #463）：先走既有 {@link AuthorizationService#decide}，再在开关打开且角色已允许时 叠加 OFFLINE / PRIVATE
 * 门禁。
 *
 * <p>唯一权限路径仍是本接口——本类是装饰器，不是第二条授权通道。flag 关或委托已拒绝时原样返回，保证默认关零变化。 缺侧车（空治理）不加额外拒绝。
 *
 * <p>API_KEY：本刀只挡 OFFLINE，不做 owner 匹配（Key 名称不是账号归属模型）。
 */
public final class AssetAwareAuthorizationService implements AuthorizationService {

  /** OFFLINE 拒绝理由（固定文案，进审计）。 */
  public static final String REASON_OFFLINE = "资产已安全下线";

  private static final String REASON_PRIVATE = "私有资产仅属主或管理员可访问";

  private final AuthorizationService delegate;

  private final AssetGovernanceStore store;

  private final boolean enabled;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "delegate/store 为注入共享单例，存同一引用正是意图。")
  public AssetAwareAuthorizationService(
      AuthorizationService delegate, AssetGovernanceStore store, boolean enabled) {
    this.delegate = delegate == null ? AuthorizationService.ALLOW_ALL : delegate;
    if (store == null) {
      throw new IllegalArgumentException("store 不能为空");
    }
    this.store = store;
    this.enabled = enabled;
  }

  @Override
  public Decision decide(Principal principal, Action action, ResourceRef resource) {
    Decision delegated = delegate.decide(principal, action, resource);
    if (!enabled || !delegated.allowed()) {
      return delegated;
    }
    if (resource == null || resource.id() == null || resource.id().isBlank()) {
      return delegated;
    }
    AssetGovernance governance = store.load(resource.type(), resource.id());
    if (governance == null || !governance.isPresent()) {
      return delegated;
    }
    if (governance.health() == AssetGovernance.Health.OFFLINE) {
      return Decision.denied(REASON_OFFLINE);
    }
    return privateGate(principal, governance);
  }

  /** PRIVATE：仅 USER 且 owner 存在且不匹配、又非 ADMIN 时拒绝。API_KEY / 匿名 / 无 owner 本刀不在这里拒绝。 */
  private static Decision privateGate(Principal principal, AssetGovernance governance) {
    if (governance.visibility() != AssetGovernance.Visibility.PRIVATE) {
      return Decision.ALLOWED;
    }
    Principal subject = principal == null ? Principal.anonymous() : principal;
    if (subject.kind() != Principal.Kind.USER) {
      return Decision.ALLOWED;
    }
    if (subject.hasRole(Role.ADMIN)) {
      return Decision.ALLOWED;
    }
    String owner = governance.owner();
    if (owner == null || owner.isBlank()) {
      return Decision.ALLOWED;
    }
    if (owner.equals(subject.id())) {
      return Decision.ALLOWED;
    }
    return Decision.denied(REASON_PRIVATE);
  }
}
