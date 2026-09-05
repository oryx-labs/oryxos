package io.oryxos.core.metrics;

/**
 * 业务指标契约（023）：LLM 调用/token/工具调用/策略拦截/fallback 切换五类计数——供监控栈聚合与告警。
 *
 * <p>依赖倒置：core/provider 的埋点只认本接口，Micrometer 实现在装配层（oryxos-cli 的 MicrometerMetricsRecorder）；无监控上下文（如
 * oryxos chat）用 {@link #NOOP} 兜底—— StreamListener.NOOP / ToolPolicyService.ALLOW_ALL 同款零破坏锚点惯例。
 *
 * <p>实现纪律：任何埋点异常不得影响主链路（FR-010）——实现方法内部自行吞异常；指标供监控聚合、 审计供精确回放，二者正交，指标绝不改变审计落库口径。
 */
public interface MetricsRecorder {

  /** 空实现：无监控上下文场景与旧构造兼容位。 */
  MetricsRecorder NOOP = new MetricsRecorder() {};

  /** 一次 LLM 调用尝试（fallback 场景每次尝试各计一次，与 llm_calls 行数同口径）。 */
  default void recordLlmCall(String provider, String model, boolean success, long durationMs) {}

  /** 成功调用的 token 消耗。 */
  default void recordLlmTokens(
      String provider, String model, Integer promptTokens, Integer completionTokens) {}

  /** 一次工具调用（含被策略拦截的失败）。 */
  default void recordToolInvocation(String tool, boolean success) {}

  /** 020 策略拦截一次。 */
  default void recordPolicyBlock(String tool) {}

  /** 一次 fallback 切换（from → to）。 */
  default void recordFallbackSwitch(String from, String to) {}

  /**
   * 入站 ASR（语音/视频音轨）一次尝试。
   *
   * @param channel 渠道类型（feishu/wecom/dingtalk）
   * @param mediaType audio / video
   * @param success 是否得到非空转写
   * @param reason ok / empty / ffmpeg / whisper / no_asr / disabled / host_denied / oversized …
   */
  default void recordInboundAsr(String channel, String mediaType, boolean success, String reason) {}

  /**
   * 入站媒体下载一次。
   *
   * @param reason ok / http / host_denied / oversized / decrypt / timeout / other
   */
  default void recordInboundMediaDownload(
      String channel, String mediaType, boolean success, String reason) {}
}
