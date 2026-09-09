package io.oryxos.web.audit;

import io.oryxos.storage.LlmCall;
import io.oryxos.storage.LlmCallRepository;
import io.oryxos.storage.ToolInvocation;
import io.oryxos.storage.ToolInvocationRepository;
import io.oryxos.web.controller.dto.AuditGroupView;
import io.oryxos.web.controller.dto.LlmCallView;
import io.oryxos.web.controller.dto.LlmSummaryView;
import io.oryxos.web.controller.dto.ToolInvocationView;
import io.oryxos.web.controller.dto.ToolSummaryView;
import io.oryxos.web.controller.dto.TraceTimelineView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 审计聚合（016 审计看板）：只读两张审计表，Java 内存 for 循环聚合（对齐 KnowledgeMetricsService 范式，无 SQL GROUP BY）。
 *
 * <p>成本口径（G2）：未计量（costMicros=null，失败或未定价）的调用不计入 totalCostMicros；无任何已计量调用时返回 null。
 */
@org.springframework.stereotype.Service
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "repository 为 Spring 注入的共享单例，构造注入存同一引用正是意图。")
public class AuditMetricsService {

  private static final String UNASSIGNED = "(未归属)";

  private final LlmCallRepository llmCallRepository;
  private final ToolInvocationRepository toolInvocationRepository;

  public AuditMetricsService(
      LlmCallRepository llmCallRepository, ToolInvocationRepository toolInvocationRepository) {
    this.llmCallRepository = llmCallRepository;
    this.toolInvocationRepository = toolInvocationRepository;
  }

  public LlmSummaryView llmSummary(Instant from, Instant to) {
    List<LlmCall> calls = llmCallRepository.findByCreatedAtBetween(from, to);
    long count = calls.size();
    long successCount = calls.stream().filter(LlmCall::isSuccess).count();
    double successRate = count == 0 ? 0.0 : (double) successCount / count;
    long totalPromptTokens =
        calls.stream().mapToLong(c -> c.getPromptTokens() == null ? 0 : c.getPromptTokens()).sum();
    long totalCompletionTokens =
        calls.stream()
            .mapToLong(c -> c.getCompletionTokens() == null ? 0 : c.getCompletionTokens())
            .sum();
    Long totalCostMicros = sumCost(calls);
    long avgDurationMs =
        count == 0
            ? 0
            : Math.round(calls.stream().mapToLong(LlmCall::getDurationMs).average().orElse(0));
    return new LlmSummaryView(
        count,
        successCount,
        successRate,
        totalPromptTokens,
        totalCompletionTokens,
        totalCostMicros,
        avgDurationMs);
  }

  public ToolSummaryView toolSummary(Instant from, Instant to) {
    List<ToolInvocation> invs = toolInvocationRepository.findByCreatedAtBetween(from, to);
    long count = invs.size();
    long successCount = invs.stream().filter(ToolInvocation::isSuccess).count();
    double successRate = count == 0 ? 0.0 : (double) successCount / count;
    long avgDurationMs =
        count == 0
            ? 0
            : Math.round(
                invs.stream().mapToLong(ToolInvocation::getDurationMs).average().orElse(0));
    return new ToolSummaryView(count, successCount, successRate, avgDurationMs);
  }

  public List<AuditGroupView> llmByModel(Instant from, Instant to) {
    return llmGroup(from, to, LlmCall::getModel);
  }

  public List<AuditGroupView> llmByProvider(Instant from, Instant to) {
    return llmGroup(from, to, LlmCall::getProvider);
  }

  public List<AuditGroupView> llmByAgent(Instant from, Instant to) {
    return llmGroup(from, to, LlmCall::getProfileName);
  }

  public List<AuditGroupView> toolByName(Instant from, Instant to) {
    Map<String, List<ToolInvocation>> grouped =
        toolInvocationRepository.findByCreatedAtBetween(from, to).stream()
            .collect(
                Collectors.groupingBy(t -> t.getToolName() == null ? UNASSIGNED : t.getToolName()));
    return grouped.entrySet().stream()
        .map(
            e -> {
              List<ToolInvocation> ts = e.getValue();
              long successCount = ts.stream().filter(ToolInvocation::isSuccess).count();
              return new AuditGroupView(e.getKey(), ts.size(), successCount, null);
            })
        .sorted(Comparator.comparingLong(AuditGroupView::count).reversed())
        .toList();
  }

  public List<LlmCallView> llmList(Instant from, Instant to, int limit) {
    return llmCallRepository.findByCreatedAtBetween(from, to).stream()
        .sorted(
            Comparator.comparing(
                LlmCall::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
        .limit(limit)
        .map(LlmCallView::from)
        .toList();
  }

  public List<ToolInvocationView> toolList(Instant from, Instant to, int limit) {
    return toolList(from, to, limit, null, null);
  }

  public List<ToolInvocationView> toolList(Instant from, Instant to, int limit, String blockedBy) {
    return toolList(from, to, limit, blockedBy, null);
  }

  /**
   * 020：blockedBy 非空时只返回该拦截来源的记录（如 'policy'=策略拒绝，FR-006 可筛口径）。 024：executionBackend
   * 非空时按执行后端筛选（SC-007；历史行 NULL ≡ local，不匹配 'docker' 但匹配 null 查询由前端处理）。
   */
  public List<ToolInvocationView> toolList(
      Instant from, Instant to, int limit, String blockedBy, String executionBackend) {
    return toolInvocationRepository.findByCreatedAtBetween(from, to).stream()
        .filter(t -> blockedBy == null || blockedBy.equals(t.getBlockedBy()))
        .filter(t -> executionBackend == null || executionBackend.equals(t.getExecutionBackend()))
        .sorted(
            Comparator.comparing(
                ToolInvocation::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
        .limit(limit)
        .map(ToolInvocationView::from)
        .toList();
  }

  /**
   * 021 单轮全链路回放：按 trace 取两表记录，按 createdAt（完成时刻）合并排序为时间线 steps + 汇总。 TOOL 步摘要先截断 200
   * 字符再脱敏（Redactor）；未命中返回 found=false 空时间线（200 不报错）。
   */
  public TraceTimelineView traceTimeline(String traceId) {
    List<LlmCall> llmCalls = llmCallRepository.findByTraceId(traceId);
    List<ToolInvocation> toolCalls = toolInvocationRepository.findByTraceId(traceId);
    if (llmCalls.isEmpty() && toolCalls.isEmpty()) {
      return new TraceTimelineView(
          traceId, false, List.of(), new TraceTimelineView.SummaryView(0, 0, 0, 0L, null, 0L));
    }

    record Event(Instant at, Object row) {}
    List<Event> events = new ArrayList<>();
    llmCalls.forEach(c -> events.add(new Event(c.getCreatedAt(), c)));
    toolCalls.forEach(t -> events.add(new Event(t.getCreatedAt(), t)));
    events.sort(Comparator.comparing(Event::at, Comparator.nullsLast(Comparator.naturalOrder())));

    List<TraceTimelineView.StepView> steps = new ArrayList<>(events.size());
    for (int i = 0; i < events.size(); i++) {
      Object row = events.get(i).row();
      steps.add(
          row instanceof LlmCall llm ? llmStep(i + 1, llm) : toolStep(i + 1, (ToolInvocation) row));
    }

    long totalTokens =
        llmCalls.stream().mapToLong(c -> c.getTotalTokens() == null ? 0 : c.getTotalTokens()).sum();
    long totalDurationMs =
        llmCalls.stream().mapToLong(LlmCall::getDurationMs).sum()
            + toolCalls.stream().mapToLong(ToolInvocation::getDurationMs).sum();
    TraceTimelineView.SummaryView summary =
        new TraceTimelineView.SummaryView(
            steps.size(),
            llmCalls.size(),
            toolCalls.size(),
            totalTokens,
            sumCost(llmCalls),
            totalDurationMs);
    return new TraceTimelineView(traceId, true, steps, summary);
  }

  private static TraceTimelineView.StepView llmStep(int seq, LlmCall c) {
    return new TraceTimelineView.StepView(
        seq,
        "LLM",
        c.getModel(),
        c.isSuccess(),
        c.getDurationMs(),
        c.getCreatedAt(),
        c.getPromptTokens(),
        c.getCompletionTokens(),
        c.getTotalTokens(),
        c.getCostMicros(),
        null,
        null,
        summarize(c.getErrorMessage()),
        null);
  }

  private static TraceTimelineView.StepView toolStep(int seq, ToolInvocation t) {
    return new TraceTimelineView.StepView(
        seq,
        "TOOL",
        t.getToolName(),
        t.isSuccess(),
        t.getDurationMs(),
        t.getCreatedAt(),
        null,
        null,
        null,
        null,
        summarize(t.getInputJson()),
        summarize(t.getResultJson()),
        summarize(t.getErrorMessage()),
        t.getBlockedBy());
  }

  /** 展示层摘要：先截断 200 字符，再统一脱敏（Redactor，FR-007/FR-008）；落库原文不动。 */
  private static String summarize(String value) {
    if (value == null) {
      return null;
    }
    String truncated = value.length() <= 200 ? value : value.substring(0, 200) + "…";
    return Redactor.redact(truncated);
  }

  private List<AuditGroupView> llmGroup(Instant from, Instant to, Function<LlmCall, String> keyFn) {
    Map<String, List<LlmCall>> grouped =
        llmCallRepository.findByCreatedAtBetween(from, to).stream()
            .collect(
                Collectors.groupingBy(
                    c -> {
                      String key = keyFn.apply(c);
                      return key == null ? UNASSIGNED : key;
                    }));
    return grouped.entrySet().stream()
        .map(
            e -> {
              List<LlmCall> cs = e.getValue();
              long successCount = cs.stream().filter(LlmCall::isSuccess).count();
              return new AuditGroupView(e.getKey(), cs.size(), successCount, sumCost(cs));
            })
        .sorted(Comparator.comparingLong(AuditGroupView::count).reversed())
        .toList();
  }

  /** 只累加已计量成本；无任何已计量调用时返回 null（未计量，区别于 0）。 */
  private static Long sumCost(List<LlmCall> calls) {
    long measured = calls.stream().filter(c -> c.getCostMicros() != null).count();
    if (measured == 0) {
      return null;
    }
    return calls.stream()
        .filter(c -> c.getCostMicros() != null)
        .mapToLong(LlmCall::getCostMicros)
        .sum();
  }
}
