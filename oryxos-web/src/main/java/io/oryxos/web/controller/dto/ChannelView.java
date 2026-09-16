package io.oryxos.web.controller.dto;

import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.policy.AssetGovernance;
import java.util.Map;

/**
 * 渠道配置视图（017）：出参 appSecret 永不回显明文——${ENV} 字面量原样保留（不含敏感），明文值以 ****** 掩码。
 *
 * <p>{@code governance} 可空：缺省未设。只回显治理字段，不承载 appSecret。
 */
public record ChannelView(
    String name,
    String type,
    String appId,
    String appSecret,
    String agent,
    boolean enabled,
    Map<String, String> extra,
    AssetGovernanceView governance) {

  /** 防御性拷贝：extra 是可变 Map，出站前固化不可变（SpotBugs EI_EXPOSE_REP / EI_EXPOSE_REP2）。 */
  public ChannelView {
    extra = extra == null ? Map.of() : Map.copyOf(extra);
  }

  /** 未设治理的便捷构造——既有 7 参调用点保持编译。 */
  public ChannelView(
      String name,
      String type,
      String appId,
      String appSecret,
      String agent,
      boolean enabled,
      Map<String, String> extra) {
    this(name, type, appId, appSecret, agent, enabled, extra, null);
  }

  private static final String MASK = "******";

  public static ChannelView from(ChannelConfig config) {
    return new ChannelView(
        config.name(),
        config.type(),
        config.appId(),
        mask(config.appSecret()),
        config.agent(),
        config.enabled(),
        maskExtra(config.extra()),
        toView(config.governance()));
  }

  /** ${} 占位保留（引导用户走环境变量）；其余一律掩码。 */
  private static String mask(String secret) {
    if (secret == null || secret.isBlank()) {
      return secret;
    }
    return secret.contains("${") ? secret : MASK;
  }

  private static Map<String, String> maskExtra(Map<String, String> extra) {
    if (extra == null || extra.isEmpty()) {
      return Map.of();
    }
    java.util.LinkedHashMap<String, String> masked = new java.util.LinkedHashMap<>();
    extra.forEach((key, value) -> masked.put(key, mask(value)));
    return Map.copyOf(masked);
  }

  /** 未设或空治理不回显对象，避免把「没元数据」写成全空块。 */
  private static AssetGovernanceView toView(AssetGovernance governance) {
    if (governance == null || !governance.isPresent()) {
      return null;
    }
    return AssetGovernanceView.from(governance);
  }

  public ChannelConfig toConfig() {
    AssetGovernance governance = this.governance == null ? null : this.governance.toModel();
    if (governance != null && !governance.isPresent()) {
      governance = AssetGovernance.empty();
    }
    return new ChannelConfig(
        name, type, appId, appSecret, agent, enabled, extra == null ? Map.of() : extra, governance);
  }
}
