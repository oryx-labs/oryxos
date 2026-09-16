package io.oryxos.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 资产治理开关（041 / #463）。
 *
 * <p>{@code oryxos.web.asset-governance.enabled} 默认 {@code false}——关闭时 {@link
 * io.oryxos.core.policy.AssetAwareAuthorizationServiceImpl} 不叠加任何拒绝，行为与无侧车时代一致。开启后仍要求 {@code
 * oryxos.web.rbac.enabled=true}：没有角色主体，资产归属门禁没有对象可判。
 */
@ConfigurationProperties(prefix = "oryxos.web.asset-governance")
public class WebAssetGovernanceProperties {

  /** 是否叠加 OFFLINE / PRIVATE 资产门禁。默认关。 */
  private boolean enabled = false;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }
}
