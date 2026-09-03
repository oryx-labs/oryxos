package io.oryxos.provider;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.provider.LlmCallAuditor;
import io.oryxos.core.provider.ModelPricing;
import io.oryxos.core.provider.PricingStore;
import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import io.oryxos.core.provider.ProviderRequest;
import io.oryxos.core.provider.ProviderResponse;
import io.oryxos.core.provider.ProviderService;
import io.oryxos.core.provider.ToolCallRequest;
import io.oryxos.core.provider.Usage;
import io.oryxos.core.session.ImageMime;
import io.oryxos.core.session.Message;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

/**
 * Provider 前台（core {@link ProviderService} 契约的 Spring AI 实现）：按 Profile 显式路由到对应
 * ChatModel，完成一次调用并落审计。
 *
 * <p>宪法 II/III：显式 name→ChatModel 映射、调用方式 {@code chatModel.call(new Prompt(...))}、 {@code
 * internalToolExecutionEnabled=false} 关闭框架自动工具执行——工具 schema 只翻译、tool call 原样透传。
 */
public class SpringAiProviderServiceImpl implements ProviderService {

  private static final Logger LOG = LoggerFactory.getLogger(SpringAiProviderServiceImpl.class);

  private final ProviderRegistry registry;
  private final Function<ProviderDef, ChatModel> chatModelBuilder;
  private final ToolSchemaAdapter adapter;
  private final LlmCallAuditor audit;
  private final PricingStore pricingStore;
  private final io.oryxos.core.metrics.MetricsRecorder metrics;

  /** 一次调用的单个尝试（023）：主 Provider 或按序备用候选，name/model 贯穿路由/Prompt/审计三处。 */
  private record Attempt(String provider, String model) {}

  // 已建的 ChatModel 缓存：key = provider name，值携带配置指纹（apiKey|baseUrl）。指纹变了原地替换旧条目——
  // 缓存大小恒等于 provider 数，反复改 key/url 不再累积不可回收的旧实例（31 节动态 provider）。
  private final Map<String, CachedModel> cache = new ConcurrentHashMap<>();

  /** 缓存条目：配置指纹 + 已建实例，指纹不变则复用。 */
  private record CachedModel(String fingerprint, ChatModel model) {}

  /** 旧五参构造保留（既有装配点/测试兼容）：无指标场景委托 NOOP（023 纯增量）。 */
  public SpringAiProviderServiceImpl(
      ProviderRegistry registry,
      Function<ProviderDef, ChatModel> chatModelBuilder,
      ToolSchemaAdapter adapter,
      LlmCallAuditor audit,
      PricingStore pricingStore) {
    this(
        registry,
        chatModelBuilder,
        adapter,
        audit,
        pricingStore,
        io.oryxos.core.metrics.MetricsRecorder.NOOP);
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "registry/builder/adapter/audit/metrics 均为装配层注入的共享单例，存同一引用正是意图")
  public SpringAiProviderServiceImpl(
      ProviderRegistry registry,
      Function<ProviderDef, ChatModel> chatModelBuilder,
      ToolSchemaAdapter adapter,
      LlmCallAuditor audit,
      PricingStore pricingStore,
      io.oryxos.core.metrics.MetricsRecorder metrics) {
    this.registry = registry;
    this.chatModelBuilder = chatModelBuilder;
    this.adapter = adapter;
    this.audit = audit;
    this.pricingStore = pricingStore;
    this.metrics = metrics;
  }

  @Override
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "日志中的 provider 名已经 sanitize() 消去 CR/LF；taint 分析不跨方法追踪该消毒，故局部抑制")
  public ProviderResponse chat(String sessionId, Profile profile, ProviderRequest request) {
    List<Attempt> attempts = attemptsOf(profile);
    RuntimeException last = null;
    for (int i = 0; i < attempts.size(); i++) {
      Attempt attempt = attempts.get(i);
      ProviderDef def = findDef(attempt, i == 0);
      if (def == null) {
        continue; // 备用候选未注册：WARN 已记，跳过不落审计（FR-008）
      }
      try {
        return chatOnce(sessionId, profile, request, attempt, def);
      } catch (RuntimeException e) {
        last = e;
        if (i + 1 >= attempts.size() || !FallbackClassifier.isSwitchable(e)) {
          throw e; // 候选耗尽或业务性失败：原样上抛最后错误（FR-002/FR-003）
        }
        switchWarn(attempt, attempts.get(i + 1), e);
      }
    }
    throw last != null ? last : new ProviderNotFoundException(profile.provider().name());
  }

  /** 单次尝试（023）：attempt 的 name/model 贯穿路由、Prompt 与审计（R2）。 */
  private ProviderResponse chatOnce(
      String sessionId,
      Profile profile,
      ProviderRequest request,
      Attempt attempt,
      ProviderDef def) {
    try {
      return invokeChat(sessionId, profile, request, attempt, def);
    } catch (RuntimeException e) {
      // 非 vision / 远程图链失效（常见 400）：先剔 http(s) media 保留本地图；仍失败再剥全部 media
      if (hasUserMedia(request) && isClientError(e)) {
        ProviderRequest withoutRemote = stripRemoteHttpMedia(request);
        if (hasUserMedia(withoutRemote)
            && countUserMedia(withoutRemote) < countUserMedia(request)) {
          LOG.warn(
              "multimodal 被拒，去掉远程 URL 图片后重试（provider={} model={}）：{}",
              sanitize(attempt.provider()),
              sanitize(attempt.model()),
              sanitize(e.getMessage()));
          try {
            return invokeChat(sessionId, profile, withoutRemote, attempt, def);
          } catch (RuntimeException e2) {
            if (!canRetryWithoutAllMedia(withoutRemote, e2)) {
              throw e2;
            }
            e = e2;
          }
        }
        LOG.warn(
            "multimodal 被拒，降级纯文本重试（provider={} model={}）：{}",
            sanitize(attempt.provider()),
            sanitize(attempt.model()),
            sanitize(e.getMessage()));
        return invokeChat(sessionId, profile, stripMedia(request), attempt, def);
      }
      throw e;
    }
  }

  private ProviderResponse invokeChat(
      String sessionId,
      Profile profile,
      ProviderRequest request,
      Attempt attempt,
      ProviderDef def) {
    ChatModel model = resolveModel(def);
    Prompt prompt = buildPrompt(profile, request, attempt.model());
    long startedAt = System.currentTimeMillis();
    ProviderResponse result;
    try {
      ChatResponse response = model.call(prompt);
      result = toProviderResponse(response);
    } catch (RuntimeException e) {
      recordFailure(sessionId, profile, attempt, e, startedAt);
      throw e;
    }
    recordSuccess(sessionId, profile, attempt, result, startedAt);
    return result;
  }

  /** 主 + 有序备用的尝试序列（023）；零 fallback 声明时长度 1，行为与现状一致。 */
  private static List<Attempt> attemptsOf(Profile profile) {
    List<Attempt> attempts = new ArrayList<>();
    attempts.add(new Attempt(profile.provider().name(), profile.provider().model()));
    profile.provider().fallbacks().forEach(f -> attempts.add(new Attempt(f.name(), f.model())));
    return attempts;
  }

  /** 主 provider 未注册直抛（现状口径）；备用候选未注册 WARN 返回 null 跳过（FR-008）。 */
  private ProviderDef findDef(Attempt attempt, boolean primary) {
    var def = registry.find(attempt.provider());
    if (def.isPresent()) {
      return def.get();
    }
    if (primary) {
      throw new ProviderNotFoundException(attempt.provider());
    }
    LOG.warn("fallback 候选 provider 未注册，跳过: {}", sanitize(attempt.provider()));
    return null;
  }

  /** 切换留痕（FR-006）：WARN 带 from→to（MDC 自带 traceId）+ 切换计数；指标异常不伤主链路。 */
  private void switchWarn(Attempt from, Attempt to, RuntimeException cause) {
    LOG.warn(
        "provider 切换: {} → {}（原因: {}）",
        sanitize(from.provider()),
        sanitize(to.provider()),
        sanitize(cause.getMessage()));
    try {
      metrics.recordFallbackSwitch(from.provider(), to.provider());
    } catch (RuntimeException ignored) {
      // FR-010：指标失败静默
    }
  }

  /**
   * 流式调用（019 R2）：{@code model.stream(prompt)} 经 {@code toIterable()} 在当前（虚拟）线程上同步迭代—— Flux/Reactor
   * 类型不出本方法（宪法 VII 边界）。只回调 content 增量（R3）；tool-call 增量在本地聚合。
   *
   * <p>降级（FR-006）：模型无流式能力（{@code stream} 抛 {@link UnsupportedOperationException}）且尚无任何输出时，
   * 回落到契约默认实现（整段 {@code chat} + 一次性回调，审计在 chat 内）。已有部分输出后失败 → 结果残缺， 失败先落账再上抛，绝不把残缺内容当完整返回。
   */
  @Override
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = {"CRLF_INJECTION_LOGS", "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE"},
      justification =
          "CRLF：与 chat 同口径，日志里的 provider 名经 sanitize() 消去 CR/LF，taint 分析不跨方法追踪该消毒；"
              + "RCN：Spring AI 的注解声称 chunk 各字段非空，但流式 chunk 的边界形态因 provider 而异，"
              + "对 generation/output 的防御性判空是有意保留的（信注解不如信线上流量）")
  public ProviderResponse chatStream(
      String sessionId,
      Profile profile,
      ProviderRequest request,
      java.util.function.Consumer<String> onToken) {
    List<Attempt> attempts = attemptsOf(profile);
    RuntimeException last = null;
    for (int i = 0; i < attempts.size(); i++) {
      Attempt attempt = attempts.get(i);
      ProviderDef def = findDef(attempt, i == 0);
      if (def == null) {
        continue;
      }
      boolean[] contentStarted = new boolean[1];
      try {
        return chatStreamOnce(sessionId, profile, request, onToken, attempt, def, contentStarted);
      } catch (RuntimeException e) {
        last = e;
        // 023 R4：首个内容片段已流出后绝不切换——重试必然重复输出，按 019 error 语义收尾（FR-007）
        if (contentStarted[0] || i + 1 >= attempts.size() || !FallbackClassifier.isSwitchable(e)) {
          throw e;
        }
        switchWarn(attempt, attempts.get(i + 1), e);
      }
    }
    throw last != null ? last : new ProviderNotFoundException(profile.provider().name());
  }

  private ProviderResponse chatStreamOnce(
      String sessionId,
      Profile profile,
      ProviderRequest request,
      java.util.function.Consumer<String> onToken,
      Attempt attempt,
      ProviderDef def,
      boolean[] contentStarted) {
    try {
      return invokeChatStream(sessionId, profile, request, onToken, attempt, def, contentStarted);
    } catch (RuntimeException e) {
      if (!contentStarted[0] && hasUserMedia(request) && isClientError(e)) {
        ProviderRequest withoutRemote = stripRemoteHttpMedia(request);
        if (hasUserMedia(withoutRemote)
            && countUserMedia(withoutRemote) < countUserMedia(request)) {
          LOG.warn(
              "multimodal 流式被拒，去掉远程 URL 图片后重试（provider={} model={}）：{}",
              sanitize(attempt.provider()),
              sanitize(attempt.model()),
              sanitize(e.getMessage()));
          try {
            return invokeChatStream(
                sessionId, profile, withoutRemote, onToken, attempt, def, contentStarted);
          } catch (RuntimeException e2) {
            boolean alreadyStreaming = contentStarted[0];
            if (alreadyStreaming || !canRetryWithoutAllMedia(withoutRemote, e2)) {
              throw e2;
            }
            e = e2;
          }
        }
        LOG.warn(
            "multimodal 流式被拒，降级纯文本重试（provider={} model={}）：{}",
            sanitize(attempt.provider()),
            sanitize(attempt.model()),
            sanitize(e.getMessage()));
        return invokeChatStream(
            sessionId, profile, stripMedia(request), onToken, attempt, def, contentStarted);
      }
      throw e;
    }
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE",
      justification =
          "Spring AI 注解声称 chunk 各字段非空，但流式 chunk 边界形态因 provider 而异，"
              + "对 generation/output 的防御性判空是有意保留的（019 裁决，信注解不如信线上流量）")
  private ProviderResponse invokeChatStream(
      String sessionId,
      Profile profile,
      ProviderRequest request,
      java.util.function.Consumer<String> onToken,
      Attempt attempt,
      ProviderDef def,
      boolean[] contentStarted) {
    ChatModel model = resolveModel(def);
    Prompt prompt = buildPrompt(profile, request, attempt.model());
    long startedAt = System.currentTimeMillis();
    StringBuilder text = new StringBuilder();
    ToolCallAggregator toolCalls = new ToolCallAggregator();
    Usage usage = null;
    try {
      for (ChatResponse chunk : model.stream(prompt).toIterable()) {
        Generation generation = chunk.getResult();
        if (generation != null && generation.getOutput() != null) {
          String delta = generation.getOutput().getText();
          if (delta != null && !delta.isEmpty()) {
            text.append(delta);
            contentStarted[0] = true;
            onToken.accept(delta);
          }
          toolCalls.accept(generation.getOutput().getToolCalls());
          if (!toolCalls.isEmpty()) {
            contentStarted[0] = true; // tool-call 增量同属实质输出（R4 保守口径）
          }
        }
        Usage chunkUsage = extractUsage(chunk);
        // 多数 provider 只在末尾 chunk 带真实 usage，中间是空/零值——只保留最后一个有效值
        if (chunkUsage != null
            && chunkUsage.totalTokens() != null
            && chunkUsage.totalTokens() > 0) {
          usage = chunkUsage;
        }
      }
    } catch (UnsupportedOperationException e) {
      if (text.isEmpty() && toolCalls.isEmpty()) {
        // 模型无流式能力且零输出：降级为本尝试的整段调用（审计仍恰好一条；023 收窄到"单次尝试"内，
        // 不再走 this.chat——否则会从主重新展开整个 fallback 序列）
        ProviderResponse whole = chatOnce(sessionId, profile, request, attempt, def);
        if (whole.text() != null && !whole.text().isEmpty()) {
          contentStarted[0] = true;
          onToken.accept(whole.text());
        }
        return whole;
      }
      recordFailure(sessionId, profile, attempt, e, startedAt);
      throw e;
    } catch (RuntimeException e) {
      // 流式中断（网络抖动/上游截断）：结果残缺不当完整返回——失败先落账再上抛（宪法 V）
      recordFailure(sessionId, profile, attempt, e, startedAt);
      throw e;
    }
    ProviderResponse result = new ProviderResponse(text.toString(), toolCalls.build(), usage);
    recordSuccess(sessionId, profile, attempt, result, startedAt);
    return result;
  }

  /**
   * 失败审计（宪法 V）：先落账再上抛——只记成功不记失败，一次真实事故就没有痕迹。 审计自身再失败也不许反客为主：上抛的必须是模型调用的真实异常（排障首先看到的是「LLM 调
   * 400」而非「审计存储抖动」），审计异常挂 suppressed + ERROR 日志独立告警。
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "日志里的 provider 名经 sanitize() 消去 CR/LF，taint 分析不跨方法追踪该消毒")
  private void recordFailure(
      String sessionId, Profile profile, Attempt attempt, RuntimeException e, long startedAt) {
    long durationMs = System.currentTimeMillis() - startedAt;
    try {
      audit.record(
          sessionId,
          profile.name(),
          attempt.provider(),
          attempt.model(),
          null,
          null,
          false,
          e.getMessage(),
          durationMs);
    } catch (RuntimeException auditFailure) {
      LOG.error(
          "LLM 调用失败的审计落库也失败（主异常照常上抛）: provider={}", sanitize(attempt.provider()), auditFailure);
      e.addSuppressed(auditFailure);
    }
    try {
      metrics.recordLlmCall(attempt.provider(), attempt.model(), false, durationMs);
    } catch (RuntimeException ignored) {
      // FR-010：指标失败静默
    }
  }

  /**
   * 成功审计 fail-open：调用已成功、token 已消耗，审计存储抖动不应让调用方丢掉这次完整回答 （宪法 V 约束的是实现上不许省审计，不是拿审计故障牺牲用户请求）；失败走 ERROR
   * 日志独立告警。
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "日志里的 provider 名经 sanitize() 消去 CR/LF，taint 分析不跨方法追踪该消毒")
  private void recordSuccess(
      String sessionId, Profile profile, Attempt attempt, ProviderResponse result, long startedAt) {
    long durationMs = System.currentTimeMillis() - startedAt;
    try {
      audit.record(
          sessionId,
          profile.name(),
          attempt.provider(),
          attempt.model(),
          result.usage(),
          computeCost(attempt.provider(), attempt.model(), result.usage()),
          true,
          null,
          durationMs);
    } catch (RuntimeException auditFailure) {
      LOG.error(
          "成功 LLM 调用的审计落库失败（结果照常返回）: provider={}", sanitize(attempt.provider()), auditFailure);
    }
    try {
      metrics.recordLlmCall(attempt.provider(), attempt.model(), true, durationMs);
      if (result.usage() != null) {
        metrics.recordLlmTokens(
            attempt.provider(),
            attempt.model(),
            result.usage().promptTokens(),
            result.usage().completionTokens());
      }
    } catch (RuntimeException ignored) {
      // FR-010：指标失败静默
    }
    // 021 日志与审计互查（SC-007）：处理路径关键日志点——MDC 自动携带 traceId，不记 prompt 内容
    LOG.info(
        "LLM 调用完成: provider={} model={} totalTokens={} durationMs={}",
        sanitize(attempt.provider()),
        sanitize(attempt.model()),
        result.usage() == null ? null : result.usage().totalTokens(),
        durationMs);
  }

  /**
   * 流式 tool-call 聚合器（019 R2）：兼容两种 chunk 形态——「合并式」（一个 chunk 携带完整 tool call）与 「增量式」（同一 id 的 arguments
   * 分片到达，或 id 只在首片、后续片 id 为空）。按 id 归组、arguments 顺序拼接。
   */
  private static final class ToolCallAggregator {

    private final Map<String, PendingCall> byId = new java.util.LinkedHashMap<>();
    private PendingCall current;

    private static final class PendingCall {
      private String name;
      private final StringBuilder arguments = new StringBuilder();
    }

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
        value = "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE",
        justification = "Spring AI 注解声称 ToolCall 各字段非空，但流式 chunk 增量形态因 provider 而异，防御性判空有意保留")
    void accept(List<AssistantMessage.ToolCall> calls) {
      if (calls == null) {
        return;
      }
      for (AssistantMessage.ToolCall call : calls) {
        String id = call.id();
        if (id != null && !id.isBlank()) {
          current = byId.computeIfAbsent(id, k -> new PendingCall());
        } else if (current == null) {
          // 首片就无 id 的异常形态：给个合成 id 兜底（真实 provider 首片必带 id）
          current = byId.computeIfAbsent("stream-call-" + byId.size(), k -> new PendingCall());
        }
        if (call.name() != null && !call.name().isBlank()) {
          current.name = call.name();
        }
        if (call.arguments() != null) {
          current.arguments.append(call.arguments());
        }
      }
    }

    boolean isEmpty() {
      return byId.isEmpty();
    }

    List<ToolCallRequest> build() {
      return byId.entrySet().stream()
          .map(
              e ->
                  new ToolCallRequest(
                      e.getKey(), e.getValue().name, e.getValue().arguments.toString()))
          .toList();
    }
  }

  /** 日志参数消毒：去掉换行，防日志伪造（CRLF injection）。 */
  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }

  /** 按 (provider, model) 查价算成本（微元）；失败/查不到价 → null（未计量）。 */
  private Long computeCost(String providerName, String model, Usage usage) {
    if (usage == null || usage.totalTokens() == null) {
      return null;
    }
    return pricingStore.find(providerName, model).map(p -> computeMicros(usage, p)).orElse(null);
  }

  private static Long computeMicros(Usage usage, ModelPricing pricing) {
    Double promptPrice = pricing.promptPrice();
    Double completionPrice = pricing.completionPrice();
    if (promptPrice == null && completionPrice == null) {
      return null;
    }
    long micros = 0;
    if (usage.promptTokens() != null && promptPrice != null) {
      micros += Math.round(usage.promptTokens() * promptPrice);
    }
    if (usage.completionTokens() != null && completionPrice != null) {
      micros += Math.round(usage.completionTokens() * completionPrice);
    }
    return micros;
  }

  /** 按 provider 名缓存已建的 ChatModel；同名下 key/url 变化即原地重建替换（provider CRUD 改了配置立即生效，旧实例可回收）。 */
  private ChatModel resolveModel(ProviderDef def) {
    String fingerprint = def.apiKey() + "|" + def.baseUrl();
    return cache
        .compute(
            def.name(),
            (name, cached) ->
                cached != null && cached.fingerprint().equals(fingerprint)
                    ? cached
                    : new CachedModel(fingerprint, chatModelBuilder.apply(def)))
        .model();
  }

  /** {@code model} 为本次尝试的模型名（023）：备用候选必须用备用模型名，不再固定取主声明。 */
  private Prompt buildPrompt(Profile profile, ProviderRequest request, String model) {
    OpenAiChatOptions.Builder options =
        OpenAiChatOptions.builder()
            .model(model)
            .internalToolExecutionEnabled(Boolean.FALSE); // 执行权只在 ToolExecutor（17 节）
    if (profile.provider().temperature() != null) {
      options.temperature(profile.provider().temperature());
    }
    List<ToolCallback> callbacks = adapter.toSpringAiTools(request.availableTools());
    if (!callbacks.isEmpty()) {
      options.toolCallbacks(callbacks);
    }
    // 结构化消息透传（31 节修复）：system + 逐条对话消息，保留 assistant tool_calls / tool tool_call_id 配对，
    // 让模型看出工具已调过、继续下一步而不是反复重调。
    List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
    if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
      messages.add(new SystemMessage(request.systemPrompt()));
    }
    for (Message message : request.messages()) {
      messages.add(toSpringMessage(message));
    }
    return new Prompt(messages, options.build());
  }

  private static org.springframework.ai.chat.messages.Message toSpringMessage(Message message) {
    if (Message.ROLE_USER.equals(message.role())) {
      List<Media> media = toSpringMedia(message.media());
      if (media.isEmpty()) {
        return new UserMessage(message.content());
      }
      return UserMessage.builder().text(message.content()).media(media).build();
    }
    if (Message.ROLE_TOOL.equals(message.role())) {
      String id = message.toolCallId();
      // 无 id 的工具结果（旧格式历史，或被截断得没了配对的 assistant tool_call）：不发成协议级 tool 消息——
      // 否则 OpenAI 会 400「tool 必须紧跟带 tool_calls 的 assistant」。降级成信息性 user 文本喂给模型。
      if (id == null || id.isBlank()) {
        return new UserMessage("[工具 " + message.toolName() + " 返回] " + message.content());
      }
      return ToolResponseMessage.builder()
          .responses(
              List.of(
                  new ToolResponseMessage.ToolResponse(id, message.toolName(), message.content())))
          .build();
    }
    // assistant：带 tool_calls（含 id）才能让下一轮的 tool 结果配上对
    if (message.toolCalls().isEmpty()) {
      return new AssistantMessage(message.content());
    }
    List<AssistantMessage.ToolCall> toolCalls =
        message.toolCalls().stream()
            .map(
                tc ->
                    new AssistantMessage.ToolCall(
                        tc.id() == null ? "" : tc.id(), "function", tc.name(), tc.argumentsJson()))
            .toList();
    return AssistantMessage.builder()
        .content(message.content())
        .properties(Map.of())
        .toolCalls(toolCalls)
        .build();
  }

  private static List<Media> toSpringMedia(List<Message.MediaPart> parts) {
    if (parts == null || parts.isEmpty()) {
      return List.of();
    }
    List<Media> media = new ArrayList<>(parts.size());
    for (Message.MediaPart part : parts) {
      Media m = toSpringMedia(part);
      if (m != null) {
        media.add(m);
      }
    }
    return media;
  }

  private static Media toSpringMedia(Message.MediaPart part) {
    if (part == null || part.uri() == null || part.uri().isBlank()) {
      return null;
    }
    String uri = part.uri().strip();
    MimeType mime = resolveMime(part.mimeType(), uri);
    try {
      if (ImageMime.isHttpUrl(uri)) {
        return new Media(mime, URI.create(uri));
      }
      Path path = Path.of(uri);
      if (!Files.isRegularFile(path)) {
        LOG.warn("跳过不可读的本地图片: {}", sanitize(uri));
        return null;
      }
      if (!ImageMime.hasRecognizedMagic(path)) {
        LOG.warn("跳过无有效图片魔数的本地文件: {}", sanitize(uri));
        return null;
      }
      return new Media(mime, new FileSystemResource(path));
    } catch (RuntimeException e) {
      LOG.warn("构造 Media 失败（uri={}）：{}", sanitize(uri), sanitize(e.getMessage()));
      return null;
    }
  }

  private static MimeType resolveMime(String declared, String uri) {
    String raw = declared;
    if (raw == null || raw.isBlank()) {
      if (ImageMime.isHttpUrl(uri)) {
        raw = ImageMime.fromPath(uri);
      } else {
        raw = ImageMime.probeFile(Path.of(uri));
      }
    }
    try {
      return MimeTypeUtils.parseMimeType(raw);
    } catch (RuntimeException e) {
      return MimeTypeUtils.IMAGE_JPEG;
    }
  }

  private static boolean hasUserMedia(ProviderRequest request) {
    return countUserMedia(request) > 0;
  }

  /** multimodal 二次降级（剥全部 media）前：请求仍有 user media 且错误为可剥的客户端 4xx。 */
  private static boolean canRetryWithoutAllMedia(ProviderRequest request, RuntimeException error) {
    return hasUserMedia(request) && isClientError(error);
  }

  private static int countUserMedia(ProviderRequest request) {
    if (request == null || request.messages() == null) {
      return 0;
    }
    int n = 0;
    for (Message message : request.messages()) {
      if (Message.ROLE_USER.equals(message.role())) {
        n += message.media().size();
      }
    }
    return n;
  }

  /** 去掉 http(s) 远程 media，保留本地路径。会话历史里过期 COS/OSS 签名链常导致整包 multimodal 400； 入站通道已落盘的新图应仍可送 Vision。 */
  private static ProviderRequest stripRemoteHttpMedia(ProviderRequest request) {
    List<Message> stripped = new ArrayList<>(request.messages().size());
    for (Message message : request.messages()) {
      if (message.media().isEmpty()) {
        stripped.add(message);
        continue;
      }
      List<Message.MediaPart> localOnly =
          message.media().stream()
              .filter(p -> p != null && p.uri() != null && !ImageMime.isHttpUrl(p.uri().strip()))
              .toList();
      if (localOnly.size() == message.media().size()) {
        stripped.add(message);
      } else {
        stripped.add(
            new Message(
                message.role(),
                message.content(),
                message.toolName(),
                message.toolCallId(),
                message.toolCalls(),
                localOnly));
      }
    }
    return new ProviderRequest(request.systemPrompt(), stripped, request.availableTools());
  }

  private static ProviderRequest stripMedia(ProviderRequest request) {
    List<Message> stripped = new ArrayList<>(request.messages().size());
    for (Message message : request.messages()) {
      if (message.media().isEmpty()) {
        stripped.add(message);
      } else {
        stripped.add(
            new Message(
                message.role(),
                message.content(),
                message.toolName(),
                message.toolCallId(),
                message.toolCalls(),
                List.of()));
      }
    }
    return new ProviderRequest(request.systemPrompt(), stripped, request.availableTools());
  }

  /** 4xx 类客户端错误（模型拒收图片等）——值得剥 media 再试；5xx/网络交给既有 fallback。 */
  private static boolean isClientError(RuntimeException e) {
    Throwable t = e;
    while (t != null) {
      if (t instanceof org.springframework.web.client.RestClientResponseException rest) {
        int code = rest.getStatusCode().value();
        return code >= 400
            && code < 500
            && code != 401
            && code != 403
            && code != 408
            && code != 429;
      }
      if (t
          instanceof
          org.springframework.web.reactive.function.client.WebClientResponseException web) {
        int code = web.getStatusCode().value();
        return code >= 400
            && code < 500
            && code != 401
            && code != 403
            && code != 408
            && code != 429;
      }
      if (t instanceof org.springframework.ai.retry.NonTransientAiException
          || t instanceof org.springframework.ai.retry.TransientAiException) {
        Integer code = leadingHttpStatus(t.getMessage());
        if (code != null) {
          return code >= 400
              && code < 500
              && code != 401
              && code != 403
              && code != 408
              && code != 429;
        }
      }
      t = t.getCause();
    }
    return false;
  }

  private static Integer leadingHttpStatus(String message) {
    if (message == null) {
      return null;
    }
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("^(\\d{3}) - ").matcher(message);
    return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
  }

  private static ProviderResponse toProviderResponse(ChatResponse response) {
    Generation generation = response.getResult();
    AssistantMessage output = generation.getOutput();
    String text = output.getText();
    List<ToolCallRequest> toolCalls =
        output.getToolCalls().stream()
            .map(call -> new ToolCallRequest(call.id(), call.name(), call.arguments()))
            .toList();
    return new ProviderResponse(text, toolCalls, extractUsage(response));
  }

  private static Usage extractUsage(ChatResponse response) {
    if (response.getMetadata().getUsage() == null) {
      return null;
    }
    org.springframework.ai.chat.metadata.Usage usage = response.getMetadata().getUsage();
    return new Usage(usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
  }
}
