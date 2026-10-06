package io.oryxos.core.profile;

/**
 * 达到 max_iterations 时的收敛策略（每个 Agent 在 AGENT.md frontmatter 的 {@code settings.max_iterations_mode}
 * 各自声明；缺省 = {@link #ERROR}）。做成枚举而非布尔开关，便于后续扩展更多收敛方式而不改签名。
 */
public enum MaxIterationsMode {
  /** 默认：直接返回哨兵串「达到最大轮数」，上层据此抛异常、本轮标记为失败，不产出任何答复。 */
  ERROR,

  /** 追加一次「无工具」的收尾模型调用，逼模型基于已累积的工具结果给出尽力而为的答复并写回 session； 本轮仍按「达到最大轮数」标记为失败——只是不再丢弃已有工作成果。 */
  SUMMARIZE;

  /** 未声明时的缺省策略。 */
  public static final MaxIterationsMode DEFAULT = ERROR;

  /** 宽松解析 frontmatter 值：去空白、大小写不敏感；空值或无法识别时回退 {@link #DEFAULT}。 */
  public static MaxIterationsMode fromConfig(String raw) {
    if (raw == null) {
      return DEFAULT;
    }
    String value = raw.strip();
    if (value.isEmpty()) {
      return DEFAULT;
    }
    for (MaxIterationsMode mode : values()) {
      if (mode.name().equalsIgnoreCase(value)) {
        return mode;
      }
    }
    return DEFAULT;
  }
}
