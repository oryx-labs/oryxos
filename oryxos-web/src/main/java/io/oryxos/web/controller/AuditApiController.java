package io.oryxos.web.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxos.web.audit.AuditMetricsService;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.AuditGroupView;
import io.oryxos.web.controller.dto.LlmCallView;
import io.oryxos.web.controller.dto.LlmSummaryView;
import io.oryxos.web.controller.dto.ToolInvocationView;
import io.oryxos.web.controller.dto.ToolSummaryView;
import io.oryxos.web.controller.dto.TraceTimelineView;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计查询接口（016 审计看板）：只读聚合两张审计表，供报表看板消费。
 *
 * <p>时间窗 from/to 为 ISO-8601 Instant 字符串，可空（缺省最近 30 天）。聚合只读，无删除/修改路径（FR-004）。
 */
@SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification = "core-stage web API is unauthenticated by design (internal network + gateway).")
@RestController
@RequestMapping("/api/v1/audit")
public class AuditApiController {

  private static final int MAX_LIMIT = 500;

  private final AuditMetricsService metricsService;

  public AuditApiController(AuditMetricsService metricsService) {
    this.metricsService = metricsService;
  }

  @GetMapping("/llm/summary")
  public ApiResponse<LlmSummaryView> llmSummary(
      @RequestParam(name = "from", required = false) String from,
      @RequestParam(name = "to", required = false) String to) {
    Instant[] range = range(from, to);
    return ApiResponse.ok(metricsService.llmSummary(range[0], range[1]));
  }

  @GetMapping("/tool/summary")
  public ApiResponse<ToolSummaryView> toolSummary(
      @RequestParam(name = "from", required = false) String from,
      @RequestParam(name = "to", required = false) String to) {
    Instant[] range = range(from, to);
    return ApiResponse.ok(metricsService.toolSummary(range[0], range[1]));
  }

  @GetMapping("/llm/by-model")
  public ApiResponse<List<AuditGroupView>> llmByModel(
      @RequestParam(name = "from", required = false) String from,
      @RequestParam(name = "to", required = false) String to) {
    Instant[] range = range(from, to);
    return ApiResponse.ok(metricsService.llmByModel(range[0], range[1]));
  }

  @GetMapping("/llm/by-provider")
  public ApiResponse<List<AuditGroupView>> llmByProvider(
      @RequestParam(name = "from", required = false) String from,
      @RequestParam(name = "to", required = false) String to) {
    Instant[] range = range(from, to);
    return ApiResponse.ok(metricsService.llmByProvider(range[0], range[1]));
  }

  @GetMapping("/tool/by-name")
  public ApiResponse<List<AuditGroupView>> toolByName(
      @RequestParam(name = "from", required = false) String from,
      @RequestParam(name = "to", required = false) String to) {
    Instant[] range = range(from, to);
    return ApiResponse.ok(metricsService.toolByName(range[0], range[1]));
  }

  @GetMapping("/by-agent")
  public ApiResponse<List<AuditGroupView>> byAgent(
      @RequestParam(name = "from", required = false) String from,
      @RequestParam(name = "to", required = false) String to) {
    Instant[] range = range(from, to);
    return ApiResponse.ok(metricsService.llmByAgent(range[0], range[1]));
  }

  @GetMapping("/llm")
  public ApiResponse<List<LlmCallView>> llm(
      @RequestParam(name = "from", required = false) String from,
      @RequestParam(name = "to", required = false) String to,
      @RequestParam(name = "limit", defaultValue = "100") int limit) {
    Instant[] range = range(from, to);
    return ApiResponse.ok(metricsService.llmList(range[0], range[1], cap(limit)));
  }

  @GetMapping("/tool")
  public ApiResponse<List<ToolInvocationView>> tool(
      @RequestParam(name = "from", required = false) String from,
      @RequestParam(name = "to", required = false) String to,
      @RequestParam(name = "limit", defaultValue = "100") int limit,
      @RequestParam(name = "blockedBy", required = false) String blockedBy,
      @RequestParam(name = "backend", required = false) String backend) {
    Instant[] range = range(from, to);
    // 020：blockedBy=policy 只看策略拒绝的调用（FR-006）；024：backend=local/docker 按执行后端筛（SC-007）
    return ApiResponse.ok(
        metricsService.toolList(range[0], range[1], cap(limit), blockedBy, backend));
  }

  /** 021：单轮全链路时间线——未命中 200 + found=false（契约 §3，不报 404）。 */
  @GetMapping("/trace/{traceId}")
  public ApiResponse<TraceTimelineView> trace(@PathVariable("traceId") String traceId) {
    return ApiResponse.ok(metricsService.traceTimeline(traceId));
  }

  private static Instant[] range(String from, String to) {
    try {
      Instant toInstant = to == null ? Instant.now() : Instant.parse(to);
      Instant fromInstant =
          from == null ? toInstant.minus(Duration.ofDays(30)) : Instant.parse(from);
      return new Instant[] {fromInstant, toInstant};
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("时间参数格式非法，应为 ISO-8601: " + e.getMessage());
    }
  }

  private static int cap(int limit) {
    return Math.min(Math.max(limit, 1), MAX_LIMIT);
  }
}
