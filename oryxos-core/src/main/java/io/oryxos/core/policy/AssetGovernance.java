package io.oryxos.core.policy;

import java.util.Locale;
import java.util.Objects;

/**
 * 资产治理侧车模型（041 / #463）：落在资产目录旁的 {@code GOVERNANCE.yml}，描述 owner / 可见性 / 健康态。
 *
 * <p>缺文件或字段全空视为「未设治理」——装饰器不得额外拒绝（存量兼容）。{@code riskLevel} 与 {@code version} 本刀只承载展示/审计，不参与裁决。
 */
public record AssetGovernance(
    String owner, String version, Visibility visibility, String riskLevel, Health health) {

  /** 可见范围：PRIVATE 仅 owner/ADMIN；WORKSPACE/PUBLIC 本刀不另加拒绝（角色矩阵已判）。 */
  public enum Visibility {
    PRIVATE,
    WORKSPACE,
    PUBLIC
  }

  /** 生命周期健康态：OFFLINE 一律拒绝；其余本刀不拦截。 */
  public enum Health {
    ACTIVE,
    DEPRECATED,
    OFFLINE
  }

  /** 空元数据：无 GOVERNANCE.yml 或解析后无有效字段。 */
  public static AssetGovernance empty() {
    return new AssetGovernance(null, null, null, null, null);
  }

  /** 是否携带任何可裁决字段（缺文件时为 false）。 */
  public boolean isPresent() {
    return (owner != null && !owner.isBlank())
        || (version != null && !version.isBlank())
        || visibility != null
        || (riskLevel != null && !riskLevel.isBlank())
        || health != null;
  }

  /** 解析可见性；未知 token 返回 null（不因脏数据误拒）。 */
  public static Visibility parseVisibility(String raw) {
    return parseEnum(Visibility.class, raw);
  }

  /** 解析健康态；未知 token 返回 null。 */
  public static Health parseHealth(String raw) {
    return parseEnum(Health.class, raw);
  }

  private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Enum.valueOf(type, raw.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  @Override
  public String toString() {
    return "AssetGovernance{owner="
        + Objects.toString(owner, "")
        + ", visibility="
        + visibility
        + ", health="
        + health
        + "}";
  }
}
