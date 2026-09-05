package io.oryxos.cli;

import io.micrometer.core.instrument.MeterRegistry;
import io.oryxos.core.metrics.MetricsRecorder;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link MetricsRecorder} 的 Micrometer 实现（023）：五类业务指标落 {@code oryxos_*}（目录见 specs/023 contracts
 * §3），经 /actuator/prometheus 暴露给企业监控栈。
 *
 * <p>实现纪律（FR-010）：全部方法吞异常记 DEBUG——任何 MeterRegistry 故障不得影响调用主链路； 标签值 null 兜底为 "unknown"（Prometheus
 * 标签不可为 null）。Counter/Timer 经 registry 按标签惰性获取（Micrometer 内部有缓存，无需自建）。
 */
public class MicrometerMetricsRecorder implements MetricsRecorder {

  private static final Logger LOG = LoggerFactory.getLogger(MicrometerMetricsRecorder.class);

  private final MeterRegistry registry;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "MeterRegistry 是 Spring 共享单例，存同一引用正是意图。")
  public MicrometerMetricsRecorder(MeterRegistry registry) {
    this.registry = registry;
  }

  @Override
  public void recordLlmCall(String provider, String model, boolean success, long durationMs) {
    try {
      registry
          .counter(
              "oryxos_llm_calls_total",
              "provider",
              tag(provider),
              "model",
              tag(model),
              "outcome",
              success ? "success" : "failure")
          .increment();
      registry
          .timer("oryxos_llm_call_duration_seconds", "provider", tag(provider), "model", tag(model))
          .record(Duration.ofMillis(durationMs));
    } catch (RuntimeException e) {
      LOG.debug("LLM 调用指标记录失败（主链路不受影响）", e);
    }
  }

  @Override
  public void recordLlmTokens(
      String provider, String model, Integer promptTokens, Integer completionTokens) {
    try {
      if (promptTokens != null && promptTokens > 0) {
        registry
            .counter(
                "oryxos_llm_tokens_total",
                "provider",
                tag(provider),
                "model",
                tag(model),
                "type",
                "prompt")
            .increment(promptTokens);
      }
      if (completionTokens != null && completionTokens > 0) {
        registry
            .counter(
                "oryxos_llm_tokens_total",
                "provider",
                tag(provider),
                "model",
                tag(model),
                "type",
                "completion")
            .increment(completionTokens);
      }
    } catch (RuntimeException e) {
      LOG.debug("token 指标记录失败（主链路不受影响）", e);
    }
  }

  @Override
  public void recordToolInvocation(String tool, boolean success) {
    try {
      registry
          .counter(
              "oryxos_tool_invocations_total",
              "tool",
              tag(tool),
              "outcome",
              success ? "success" : "failure")
          .increment();
    } catch (RuntimeException e) {
      LOG.debug("工具调用指标记录失败（主链路不受影响）", e);
    }
  }

  @Override
  public void recordPolicyBlock(String tool) {
    try {
      registry.counter("oryxos_policy_blocks_total", "tool", tag(tool)).increment();
    } catch (RuntimeException e) {
      LOG.debug("策略拦截指标记录失败（主链路不受影响）", e);
    }
  }

  @Override
  public void recordFallbackSwitch(String from, String to) {
    try {
      registry
          .counter("oryxos_fallback_switches_total", "from", tag(from), "to", tag(to))
          .increment();
    } catch (RuntimeException e) {
      LOG.debug("fallback 切换指标记录失败（主链路不受影响）", e);
    }
  }

  @Override
  public void recordInboundAsr(String channel, String mediaType, boolean success, String reason) {
    try {
      registry
          .counter(
              "oryxos_inbound_asr_total",
              "channel",
              tag(channel),
              "media",
              tag(mediaType),
              "outcome",
              success ? "success" : "failure",
              "reason",
              tag(reason))
          .increment();
    } catch (RuntimeException e) {
      LOG.debug("入站 ASR 指标记录失败（主链路不受影响）", e);
    }
  }

  @Override
  public void recordInboundMediaDownload(
      String channel, String mediaType, boolean success, String reason) {
    try {
      registry
          .counter(
              "oryxos_inbound_media_download_total",
              "channel",
              tag(channel),
              "media",
              tag(mediaType),
              "outcome",
              success ? "success" : "failure",
              "reason",
              tag(reason))
          .increment();
    } catch (RuntimeException e) {
      LOG.debug("入站媒体下载指标记录失败（主链路不受影响）", e);
    }
  }

  private static String tag(String value) {
    return value == null || value.isBlank() ? "unknown" : value;
  }
}
