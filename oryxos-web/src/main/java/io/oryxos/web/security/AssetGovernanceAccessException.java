package io.oryxos.web.security;

/**
 * 资产治理门禁拒绝（041）：Controller 在 {@code AuthorizationService.decide} 已拒绝后抛出，由 advice 映射为 403。
 *
 * <p>不另写一套权限判断——理由来自决策点返回的 {@code Decision.reason()}。
 */
public class AssetGovernanceAccessException extends RuntimeException {

  public AssetGovernanceAccessException(String reason) {
    super(reason == null || reason.isBlank() ? "被资产治理策略拒绝" : reason);
  }
}
